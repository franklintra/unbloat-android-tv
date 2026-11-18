package tv.zenhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Recepteur AirPlay audio (AirPlay 1 / RAOP), le protocole des enceintes AirPort Express.
 * Un iPhone, un iPad ou un Mac voit la TV comme une enceinte et y envoie le son en ALAC.
 *
 * - annonce Bonjour _raop._tcp via NsdManager (le mdnsd du systeme, rien a garder ouvert)
 * - serveur RTSP sur le port 5000 : OPTIONS, ANNOUNCE (SDP + cle AES chiffree RSA), SETUP, RECORD,
 *   FLUSH, SET_PARAMETER (volume), TEARDOWN
 * - trois sockets UDP par session : audio (RTP chiffre AES-CBC), controle (synchro, renvois), horloge
 * - chaque trame est jouee a l'heure donnee par les paquets de synchro, via AudioTimestamp
 *
 * Tourne dans le processus du service Keys (deja demarre au boot et tenu en vie par TCL) : start() depuis
 * Keys.onServiceConnected, reannounce() quand l'ecran se rallume ou que le Wi-Fi revient.
 * La cle RSA est celle des AirPort Express, publique depuis 2011 (shairport) : sans elle, iOS refuse la cible.
 */
final class AirPlay {

    static final String TAG = "ZenAirPlay";
    static final int RTSP_PORT = 5000, RATE = 44100;
    static final long FIXED_LATENCY_FRAMES = 11025;   // decalage fixe ajoute par shairport-sync pour iOS
    static final long DEADBAND_NS = 25_000_000L;

    static final BigInteger N = new BigInteger(
            "e7d744f2a2e2788b6c1f55a08eb70544a8fa7945aa8be6c62ce5f51cbdd4dc6842fe3d1083dd2edec1bfd4252dc02e6f398bdf0e6148ea84855e2e442da6d62664f674a1f304929ade4f6893ef2df6e711a8c77a0d91c9d980822e50d12922afea40ea9f0e14c0f76938c5f3882fc0323dd9fe55155f51bb5921c201629fd73352d5e2efaabf9ba048d7b813a2b6767f6c3ccf1eb4ce673d037b0d2ea30c5fffeb06f8d08adde409571a9c689fef10728855dd8cfb9a8bef5c8943ef3b5faa15dde698beddf3599603eb3e6f61372bb628f6559f599a78bf500687aa7f4976c0562d412956f8989e18a6355bd81597825e0fc875343ec782117625cdbf98447b", 16);
    static final BigInteger KEY_D = new BigInteger(
            "e5f00c72f577d604b9a4ce4122aa84b01743ec995acfcc7f4ab27c0b187f90665be359df1259818deeed79d3b1ef845e4ddddac9a155373b5e270d8e1315001a2e527d54cdf9000a5768bc98d4446b37bbbd00b29dd8b53062133b2a6e77f4ee32505622904da720fb1c12c03996da713a0506098edbedecf936d0fa9cbd5929abb0eda35799502f9894dcb8fc569a892d17780324a2b6c3166e346709134b854041b867706b58fef2a0db922b77628b68e69693c7af43bf2a73d0b732377a0ba17b44f051e9bf79849dcb3332571fd8a70933c2d60bdec479934a3daca40bb6f2f37c0a9d07106eadc8b369a03f2f41c880098e8add46240dac68cc5354f361", 16);

    final Context ctx;
    final String name;
    final String password;   // demande par iOS une fois par appareil, puis garde dans son trousseau
    final byte[] hw;
    volatile boolean running;
    ServerSocket server;
    NsdManager nsd;
    NsdManager.RegistrationListener reg;
    ConnectivityManager.NetworkCallback netCb;
    volatile Session current;
    static final int MAX_BODY = 4 << 20;   // SET_PARAMETER porte la pochette (centaines de Ko) ; 4 Mio suffisent
    final float volume = 1f;   // gain de la piste ; le volume AirPlay regle celui de la TV

    static AirPlay instance;

    static synchronized void start(Context c) {
        if (instance == null) { instance = new AirPlay(c.getApplicationContext()); instance.begin(); }
        else instance.reannounce();
    }
    static synchronized void stop() { if (instance != null) { instance.end(); instance = null; } }
    static synchronized void wake() { if (instance != null) instance.reannounce(); }
    static synchronized boolean busy() { return instance != null && instance.current != null; }

    AirPlay(Context c) {
        ctx = c;
        SharedPreferences p = c.getSharedPreferences("airplay", Context.MODE_PRIVATE);
        hw = unhex(hwPref(p));
        name = namePref(p);
        String pw = p.getString("password", "1234");   // code AirPlay ; chaine vide = pas de code
        password = pw.isEmpty() ? null : pw;
    }

    // ------------------------------------------------------------------ cycle de vie

    void begin() {
        running = true;
        Thread t = new Thread(new Runnable() { public void run() { serve(); } }, "airplay-rtsp");
        t.setDaemon(true);
        t.start();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { reannounce(); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), netCb);
        } catch (Exception e) { Log.w(TAG, "network callback", e); }
    }

    void end() {
        running = false;
        Session s = current; if (s != null) s.stop();
        unregister();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (netCb != null) cm.unregisterNetworkCallback(netCb);
        } catch (Exception ignored) { }
    }

    void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            try { server.bind(new InetSocketAddress(RTSP_PORT)); }
            catch (IOException e) { server.bind(new InetSocketAddress(0)); }
            Log.i(TAG, "RTSP sur le port " + server.getLocalPort());
            register();
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock connLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zenhome:airplay-conn");
            connLock.setReferenceCounted(false);
            while (running) {
                final Socket s = server.accept();
                // TV en veille rapide : le Wi-Fi la sort de veille sans ecran le temps d'un paquet, puis elle se
                // rendort. On la garde eveillee pendant l'echange RTSP ; RECORD allume ensuite l'ecran.
                connLock.acquire(30_000);
                Thread t = new Thread(new Runnable() { public void run() { new Conn(s).run(); } }, "airplay-conn");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "serveur RTSP arrete", e);
        }
    }

    // ------------------------------------------------------------------ Bonjour

    synchronized void register() {
        if (server == null || !running || reg != null) return;
        if (nsd == null) nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo si = new NsdServiceInfo();
        si.setServiceName(hex(hw).toUpperCase(Locale.ROOT) + "@" + name);
        si.setServiceType("_raop._tcp");
        si.setPort(server.getLocalPort());
        String[][] txt = {
                {"txtvers", "1"}, {"ch", "2"}, {"cn", "0,1"}, {"et", "0,1"}, {"ek", "1"}, {"sv", "false"},
                {"da", "true"}, {"sr", "44100"}, {"ss", "16"}, {"pw", "false"}, {"vn", "65537"}, {"tp", "UDP"},
                {"vs", "105.1"}, {"am", "ZenHome"}, {"sf", "0x4"}, {"md", "0,2"}};
        if (password != null) txt[9][1] = "true";   // pw
        for (String[] kv : txt) si.setAttribute(kv[0], kv[1]);
        reg = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "annonce : " + i.getServiceName()); }
            public void onRegistrationFailed(NsdServiceInfo i, int err) { Log.w(TAG, "annonce refusee " + err); synchronized (AirPlay.this) { reg = null; } }
            public void onServiceUnregistered(NsdServiceInfo i) { }
            public void onUnregistrationFailed(NsdServiceInfo i, int err) { }
        };
        try { nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, reg); }
        catch (Exception e) { Log.w(TAG, "registerService", e); reg = null; }
    }

    synchronized void unregister() {
        if (nsd != null && reg != null) { try { nsd.unregisterService(reg); } catch (Exception ignored) { } }
        reg = null;
    }

    /** Wi-Fi revenu ou ecran rallume : on reannonce si l'annonce precedente a echoue */
    synchronized void reannounce() {
        if (server == null || reg != null) return;   // le mdnsd reannonce seul au retour du Wi-Fi
        register();
    }

    // ------------------------------------------------------------------ RTSP

    final class Conn {
        final Socket sock;
        Session session;
        boolean authorized;
        String nonce;
        Conn(Socket s) { sock = s; }

        /** Digest RTSP comme shairport-sync : MD5(MD5(user:realm:code):nonce:MD5(methode:uri)) */
        boolean digestOk(String method, String hdr) {
            if (hdr == null || !hdr.startsWith("Digest ")) return false;
            String user = dq(hdr, "username"), realm = dq(hdr, "realm"), uri = dq(hdr, "uri"), response = dq(hdr, "response");
            if (user == null || realm == null || uri == null || response == null) return false;
            String ha1 = md5(user + ":" + realm + ":" + password), ha2 = md5(method + ":" + uri);
            return md5(ha1 + ":" + nonce + ":" + ha2).equalsIgnoreCase(response);
        }

        void run() {
            try {
                sock.setTcpNoDelay(true);
                // iPhone parti sans TEARDOWN : sans delai, readLine bloquait pour toujours et la session (verrous,
                // AirPlay.busy) ne finissait jamais. Silence RTSP admis tant que le son arrive.
                sock.setSoTimeout(30_000);
                InputStream in = new BufferedInputStream(sock.getInputStream());
                OutputStream out = sock.getOutputStream();
                while (running) {
                    String line;
                    try { line = readLine(in); }
                    catch (java.net.SocketTimeoutException t) {
                        if (session != null && session.active && session.recent()) continue;
                        throw t;
                    }
                    if (line == null) break;
                    if (line.isEmpty()) continue;
                    String[] rl = line.split(" ");
                    String method = rl[0];
                    Log.d(TAG, "RTSP " + line);
                    Map<String, String> h = new HashMap<>();
                    String l;
                    while ((l = readLine(in)) != null && !l.isEmpty()) {
                        int c = l.indexOf(':');
                        if (c > 0) h.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
                    }
                    if (l == null) break;
                    int len = 0;
                    try { len = Integer.parseInt(h.containsKey("content-length") ? h.get("content-length") : "0"); }
                    catch (NumberFormatException ignored) { }
                    // lu avant le code AirPlay : une longueur enorme faisait un OutOfMemoryError, qui tuait Zen Home
                    if (len < 0 || len > MAX_BODY) { Log.w(TAG, "corps RTSP refuse (" + len + " octets)"); break; }
                    byte[] body = new byte[len];
                    for (int r = 0; r < len; ) { int n = in.read(body, r, len - r); if (n < 0) throw new IOException("eof"); r += n; }

                    StringBuilder resp = new StringBuilder();
                    byte[] rbody = null;
                    String status = "200 OK";
                    boolean close = false;
                    if (password != null && !authorized) {
                        if (nonce != null && digestOk(method, h.get("authorization"))) authorized = true;
                        else {
                            if (nonce == null) nonce = newNonce();
                            else Log.w(TAG, "code AirPlay refuse pour " + sock.getInetAddress().getHostAddress());
                            status = "401 Unauthorized";
                            resp.append("WWW-Authenticate: Digest realm=\"raop\", nonce=\"").append(nonce).append("\"\r\n");
                            method = "";   // la requete n'est pas traitee
                        }
                    }
                    switch (method) {
                        case "OPTIONS":
                            resp.append("Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER\r\n");
                            break;
                        case "ANNOUNCE":
                            Session old = current;
                            if (old != null) old.stop();
                            session = new Session(new String(body, "UTF-8"));   // peut echouer : alors pas de reveil
                            wakeScreen();
                            current = session;
                            Log.i(TAG, "ANNOUNCE de " + sock.getInetAddress().getHostAddress() + " " + session.describe());
                            break;
                        case "SETUP":
                            if (session == null) { status = "455 Method Not Valid In This State"; break; }
                            String tr = h.get("transport");
                            session.setup(sock.getInetAddress(), param(tr, "control_port"), param(tr, "timing_port"));
                            resp.append("Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=")
                                .append(session.control.getLocalPort()).append(";timing_port=")
                                .append(session.timing.getLocalPort()).append(";server_port=")
                                .append(session.audio.getLocalPort()).append("\r\n");
                            resp.append("Session: 1\r\nAudio-Jack-Status: connected; type=analog\r\n");
                            break;
                        case "RECORD":
                            if (session != null) session.record();
                            resp.append("Audio-Latency: 11025\r\n");
                            break;
                        case "FLUSH":
                            if (session != null) session.flush(param(h.get("rtp-info"), "seq"));
                            break;
                        case "SET_PARAMETER": {
                            String ct = h.get("content-type");
                            if (ct != null && ct.startsWith("text/parameters")) {
                                String txt = new String(body, "UTF-8");
                                for (String p : txt.split("\r?\n")) {
                                    if (p.startsWith("volume:")) {
                                        try { setVolume(Float.parseFloat(p.substring(7).trim())); }
                                        catch (NumberFormatException ignored) { }
                                    }
                                }
                            }
                            break;
                        }
                        case "GET_PARAMETER":
                            break;
                        case "TEARDOWN":
                            if (session != null) session.stop();
                            resp.append("Connection: close\r\n");
                            close = true;
                            break;
                        case "":
                            break;
                        default:
                            status = "501 Not Implemented";   // GET /info, POST /fp-setup... : AirPlay 2, pas ici
                            break;
                    }

                    StringBuilder sb = new StringBuilder("RTSP/1.0 ").append(status).append("\r\n");
                    String cseq = h.get("cseq");
                    if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n");
                    sb.append("Server: AirTunes/105.1\r\n");
                    String chal = h.get("apple-challenge");
                    if (chal != null) {
                        String ar = appleResponse(chal, sock.getLocalAddress());
                        if (ar != null) sb.append("Apple-Response: ").append(ar).append("\r\n");
                    }
                    sb.append(resp);
                    sb.append("Content-Length: ").append(rbody == null ? 0 : rbody.length).append("\r\n\r\n");
                    out.write(sb.toString().getBytes("UTF-8"));
                    if (rbody != null) out.write(rbody);
                    out.flush();
                    if (close) break;
                }
            } catch (Exception e) {
                Log.i(TAG, "connexion fermee : " + e);
            } finally {
                if (session != null) session.stop();
                try { sock.close(); } catch (IOException ignored) { }
            }
        }
    }

    static String dq(String hdr, String key) {
        int i = hdr.indexOf(key + "=\"");
        if (i < 0) return null;
        i += key.length() + 2;
        int j = hdr.indexOf('"', i);
        return j < 0 ? null : hdr.substring(i, j);
    }

    static String md5(String s) {
        try { return hex(MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static String newNonce() {
        byte[] r = new byte[16]; new SecureRandom().nextBytes(r);
        return hex(r);
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    static int param(String header, String key) {
        if (header == null) return -1;
        for (String p : header.split(";")) {
            p = p.trim();
            if (p.startsWith(key + "=")) {
                try { return Integer.parseInt(p.substring(key.length() + 1).trim()); }
                catch (NumberFormatException e) { return -1; }
            }
        }
        return -1;
    }

    /** TV en veille rapide : on l'allume (ecran, image et son TCL) ; si l'ecran est deja allume, on ne touche a rien. */
    void wakeScreen() { wakeScreen(ctx); }

    /** Partage avec Mirror (son seul via UxPlay). */
    static void wakeScreen(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm.isInteractive()) return;
            Log.i(TAG, "TV en veille : on l'allume, ecran eteint (AudioOnly)");
            AudioOnly.arm(ctx);
            ctx.startActivity(new android.content.Intent(ctx, WakeActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            Log.w(TAG, "reveil", e);
        }
    }

    /**
     * Volume AirPlay : -144 = muet, sinon -30 (curseur au minimum) .. 0 (au maximum), lineaire en position de curseur.
     * Il pilote le vrai volume de la TV (STREAM_MUSIC, 0..100 sur cette TCL), comme une enceinte AirPlay :
     * le curseur de l'iPhone et la telecommande reglent la meme chose. La piste AirPlay reste a gain 1.
     */
    void setVolume(float v) { tvVolume(ctx, v); }

    /** Partage avec Mirror (AirPlay video) : volume AirPlay en dB -> volume TV. */
    static void tvVolume(Context ctx, float v) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int idx = v <= -30f ? 0 : Math.round((v + 30f) / 30f * max);
            idx = Math.max(0, Math.min(max, idx));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
            Log.i(TAG, "volume AirPlay " + v + " -> TV " + idx + "/" + max);
        } catch (Exception e) {
            Log.w(TAG, "volume", e);
        }
    }

    // ------------------------------------------------------------------ RSA

    String appleResponse(String challenge, InetAddress local) {
        try {
            byte[] ch = b64(challenge);
            if (ch.length > 16) return null;
            byte[] ip = local.getAddress();
            byte[] buf = new byte[Math.max(32, ch.length + ip.length + 6)];
            System.arraycopy(ch, 0, buf, 0, ch.length);
            System.arraycopy(ip, 0, buf, ch.length, ip.length);
            System.arraycopy(hw, 0, buf, ch.length + ip.length, 6);
            // PKCS#1 v1.5 type 1 (signature brute), comme RSA_private_encrypt
            byte[] em = new byte[256];
            em[0] = 0; em[1] = 1;
            int ps = 256 - 3 - buf.length;
            for (int i = 0; i < ps; i++) em[2 + i] = (byte) 0xff;
            em[2 + ps] = 0;
            System.arraycopy(buf, 0, em, 3 + ps, buf.length);
            byte[] s = fixed(new BigInteger(1, em).modPow(KEY_D, N), 256);
            String r = Base64.encodeToString(s, Base64.NO_WRAP);
            int eq = r.indexOf('=');
            return eq >= 0 ? r.substring(0, eq) : r;
        } catch (Exception e) {
            Log.w(TAG, "Apple-Challenge", e);
            return null;
        }
    }

    /** RSA-OAEP (SHA-1, MGF1) : dechiffre la cle AES de la session */
    static byte[] rsaOaepDecrypt(byte[] c) throws Exception {
        byte[] em = fixed(new BigInteger(1, c).modPow(KEY_D, N), 256);
        if (em[0] != 0) throw new IllegalArgumentException("OAEP");
        int hl = 20;
        byte[] maskedSeed = new byte[hl], maskedDb = new byte[256 - 1 - hl];
        System.arraycopy(em, 1, maskedSeed, 0, hl);
        System.arraycopy(em, 1 + hl, maskedDb, 0, maskedDb.length);
        byte[] seed = xor(maskedSeed, mgf1(maskedDb, hl));
        byte[] db = xor(maskedDb, mgf1(seed, maskedDb.length));
        int i = hl;
        while (i < db.length && db[i] == 0) i++;
        if (i >= db.length || db[i] != 1) throw new IllegalArgumentException("OAEP");
        byte[] m = new byte[db.length - i - 1];
        System.arraycopy(db, i + 1, m, 0, m.length);
        return m;
    }

    static byte[] mgf1(byte[] seed, int len) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        byte[] out = new byte[len];
        for (int c = 0, o = 0; o < len; c++) {
            sha.reset();
            sha.update(seed);
            sha.update(new byte[]{(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
            byte[] d = sha.digest();
            int n = Math.min(d.length, len - o);
            System.arraycopy(d, 0, out, o, n);
            o += n;
        }
        return out;
    }

    static byte[] xor(byte[] a, byte[] b) { byte[] r = new byte[a.length]; for (int i = 0; i < a.length; i++) r[i] = (byte) (a[i] ^ b[i]); return r; }

    static byte[] fixed(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) return b;
        byte[] r = new byte[len];
        if (b.length > len) System.arraycopy(b, b.length - len, r, 0, len);
        else System.arraycopy(b, 0, r, len - b.length, b.length);
        return r;
    }

    static byte[] b64(String s) {
        s = s.trim();
        while (s.length() % 4 != 0) s += "=";
        return Base64.decode(s, Base64.DEFAULT);
    }

    /** Adresse materielle annoncee, partagee avec Mirror (_airplay._tcp) pour ne former qu'un appareil sur iOS. */
    static synchronized String hwPref(SharedPreferences p) {
        String h = p.getString("hw", null);
        if (h == null) {
            byte[] r = new byte[6]; new SecureRandom().nextBytes(r);
            r[0] = (byte) ((r[0] & 0xfc) | 0x02);   // adresse locale, unicast
            h = hex(r); p.edit().putString("hw", h).commit();
        }
        return h;
    }

    static String namePref(SharedPreferences p) { return p.getString("name", "Chambre Franklin"); }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static byte[] unhex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }

    static int seqDiff(int a, int b) { return (short) (a - b); }   // a - b sur 16 bits signes

    // ------------------------------------------------------------------ session audio

    final class Session {
        volatile long lastAudio = android.os.SystemClock.elapsedRealtime();
        boolean recent() { return android.os.SystemClock.elapsedRealtime() - lastAudio < 60_000; }
        boolean alacCodec = true;
        int frameLen = 352, channels = 2;
        Alac alac;
        byte[] aesKey, aesIv;
        Cipher cipher;

        DatagramSocket audio, control, timing;
        InetAddress client;
        int clientControl = -1, clientTiming = -1;
        volatile boolean active = true, recording;

        // tampon circulaire de trames decodees, indexe par numero de sequence RTP
        static final int SLOTS = 1024, MASK = SLOTS - 1;
        final Object lock = new Object();
        final short[][] pcm = new short[SLOTS][];
        final int[] slotSeq = new int[SLOTS], slotLen = new int[SLOTS];
        final long[] slotTs = new long[SLOTS];
        boolean started;
        int nextSeq, latestSeq, flushSeq = -1, resendSeq;
        long nextTs;
        // ancrage : la trame anchorRtp doit sortir des haut-parleurs a anchorNs (horloge System.nanoTime)
        long anchorRtp, anchorNs;
        boolean anchored;

        AudioTrack track;
        PowerManager.WakeLock wake;
        WifiManager.WifiLock wifi;
        AudioManager am;
        boolean focusLost;
        final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int f) {
                focusLost = f == AudioManager.AUDIOFOCUS_LOSS;
                applyVolume();
            }
        };

        Session(String sdp) throws Exception {
            for (String l : sdp.split("\r?\n")) {
                l = l.trim();
                if (l.startsWith("a=rtpmap:")) {
                    alacCodec = l.contains("AppleLossless");
                    if (!alacCodec && !l.contains("L16")) throw new IllegalArgumentException("codec " + l);
                } else if (l.startsWith("a=fmtp:")) {
                    String[] f = l.substring(l.indexOf(' ') + 1).trim().split("\\s+");
                    if (f.length >= 11) {
                        frameLen = Integer.parseInt(f[0]);
                        channels = Integer.parseInt(f[6]);
                        alac = new Alac(frameLen, Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                                Integer.parseInt(f[4]), Integer.parseInt(f[5]), channels, Integer.parseInt(f[7]));
                    }
                } else if (l.startsWith("a=rsaaeskey:")) {
                    aesKey = rsaOaepDecrypt(b64(l.substring(12)));
                } else if (l.startsWith("a=aesiv:")) {
                    aesIv = b64(l.substring(8));
                }
            }
            if (alacCodec && alac == null) alac = new Alac(352, 16, 40, 10, 14, 2, 255);
            if (aesKey != null) cipher = Cipher.getInstance("AES/CBC/NoPadding");
        }

        String describe() { return (alacCodec ? "ALAC" : "L16") + " " + frameLen + " " + channels + "ch" + (aesKey != null ? " chiffre" : ""); }

        void setup(InetAddress c, int cport, int tport) throws IOException {
            client = c; clientControl = cport; clientTiming = tport;
            if (audio != null) return;
            audio = new DatagramSocket(); control = new DatagramSocket(); timing = new DatagramSocket();
            audio.setReceiveBufferSize(1 << 18);
            thread("airplay-audio", new Runnable() { public void run() { receive(audio); } });
            thread("airplay-control", new Runnable() { public void run() { receive(control); } });
            thread("airplay-timing", new Runnable() { public void run() { timingLoop(); } });
        }

        void record() {
            if (recording) return;
            recording = true;
            AudioOnly.sessionStarted();
            try {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                // l'ecran reste allume pendant la lecture : sinon la mise en veille auto de la TV coupe le Wi-Fi,
                // et si la TV etait en veille rapide (reveillee par le Wi-Fi), ce verrou l'allume.
                // Le mode TCL "audio seul" eteint quand meme la dalle.
                wake = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, "zenhome:airplay");   // rallume la TV si elle dormait
                wake.acquire();
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zenhome:airplay");
                wifi.acquire();
                am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            } catch (Exception e) { Log.w(TAG, "verrous", e); }
            thread("airplay-player", new Runnable() { public void run() { play(); } });
        }

        void flush(int untilSeq) {
            synchronized (lock) {
                for (int i = 0; i < SLOTS; i++) pcm[i] = null;
                started = false;
                anchored = false;
                flushSeq = untilSeq;
                lock.notifyAll();
            }
            AudioTrack t = track;
            if (t != null) { try { t.pause(); t.flush(); t.play(); } catch (Exception ignored) { } }
        }

        void stop() {
            if (!active) return;
            active = false;
            if (current == this) current = null;
            synchronized (lock) { lock.notifyAll(); }
            for (DatagramSocket s : new DatagramSocket[]{audio, control, timing}) if (s != null) s.close();
            try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
            try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) { }
            try { if (am != null) am.abandonAudioFocus(focus); } catch (Exception ignored) { }
            AudioOnly.sessionEnded();
            Log.i(TAG, "session terminee");
        }

        void applyVolume() {
            AudioTrack t = track;
            if (t != null) { try { t.setVolume(focusLost ? 0f : volume); } catch (Exception ignored) { } }
        }

        void thread(String n, Runnable r) { Thread t = new Thread(r, n); t.setDaemon(true); t.start(); }

        // ---- reception UDP (audio et controle)

        void receive(DatagramSocket sock) {
            byte[] b = new byte[2048];
            byte[] dec = new byte[2048];
            DatagramPacket p = new DatagramPacket(b, b.length);
            while (active) {
                try {
                    p.setLength(b.length);
                    sock.receive(p);
                    int len = p.getLength();
                    if (len < 4) continue;
                    int type = b[1] & 0x7f;
                    if (type == 0x60) onAudio(b, 0, len, dec, true);
                    else if (type == 0x56) onAudio(b, 4, len - 4, dec, false);
                    else if (type == 0x54 && len >= 20) onSync(b);
                } catch (IOException e) {
                    if (active) Log.w(TAG, "reception", e);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "paquet", e);
                }
            }
        }

        boolean firstSync = true, firstAudio = true;
        void onSync(byte[] b) {
            long now = System.nanoTime();
            Log.d(TAG, "synchro rtpLessLatency=" + u32(b, 4) + " rtpNow=" + u32(b, 16));
            if (firstSync) { firstSync = false; Log.i(TAG, "premier paquet de synchro"); }
            long rtpLessLatency = u32(b, 4);
            synchronized (lock) {
                anchorRtp = rtpLessLatency - FIXED_LATENCY_FRAMES;   // cette trame-la sort maintenant
                anchorNs = now;
                anchored = true;
                lock.notifyAll();
            }
        }

        // fils audio et controle (paquets renvoyes) : un seul Cipher et un seul Alac, a etat, d'ou le verrou
        synchronized void onAudio(byte[] b, int off, int len, byte[] dec, boolean live) throws Exception {
            if (len < 12) return;
            lastAudio = android.os.SystemClock.elapsedRealtime();
            int seq = ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
            long ts = u32(b, off + 4);
            int plen = len - 12, poff = off + 12;
            synchronized (lock) {
                if (flushSeq >= 0 && seqDiff(seq, flushSeq) < 0) return;   // anterieur au FLUSH
            }
            byte[] payload = b;
            if (cipher != null) {
                int n16 = plen & ~15;
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(aesIv));
                if (n16 > 0) cipher.doFinal(b, poff, n16, dec, 0);
                System.arraycopy(b, poff + n16, dec, n16, plen - n16);
                payload = dec; poff = 0;
            }
            short[] out = new short[frameLen * channels];
            int n;
            if (alacCodec) n = alac.decode(payload, poff, plen, out);
            else {
                n = Math.min(plen / (2 * channels), frameLen);
                for (int i = 0; i < n * channels; i++) out[i] = (short) (((payload[poff + 2 * i] & 0xff) << 8) | (payload[poff + 2 * i + 1] & 0xff));
            }
            if (n <= 0) { Log.w(TAG, "trame illisible seq " + seq + " len " + plen); return; }
            if (firstAudio) { firstAudio = false; Log.i(TAG, "premier paquet audio seq " + seq + " ts " + ts); }

            int resendFrom = -1, resendCount = 0;
            synchronized (lock) {
                if (!started) {
                    started = true; nextSeq = seq; latestSeq = seq; nextTs = ts; flushSeq = -1;
                } else {
                    if (seqDiff(seq, nextSeq) < 0) return;                 // trop tard, deja joue
                    int gap = seqDiff(seq, latestSeq);
                    if (gap > 1 && live && gap < 128) { resendFrom = (latestSeq + 1) & 0xffff; resendCount = gap - 1; }
                    if (gap > 0) latestSeq = seq;
                }
                if (seqDiff(seq, nextSeq) >= SLOTS) {                        // tres en avance : on recale
                    for (int i = 0; i < SLOTS; i++) pcm[i] = null;
                    nextSeq = seq; latestSeq = seq; nextTs = ts;
                }
                int s = seq & MASK;
                pcm[s] = out; slotSeq[s] = seq; slotTs[s] = ts; slotLen[s] = n;
                lock.notifyAll();
            }
            if (resendFrom >= 0) requestResend(resendFrom, resendCount);
        }

        void requestResend(int first, int count) {
            if (clientControl <= 0) return;
            byte[] r = {(byte) 0x80, (byte) 0xd5, (byte) (resendSeq >> 8), (byte) resendSeq,
                    (byte) (first >> 8), (byte) first, (byte) (count >> 8), (byte) count};
            resendSeq++;
            try { control.send(new DatagramPacket(r, r.length, client, clientControl)); } catch (Exception ignored) { }
        }

        // ---- horloge : repond aux demandes NTP de l'emetteur
        void timingLoop() {
            byte[] b = new byte[128];
            DatagramPacket p = new DatagramPacket(b, b.length);
            while (active) {
                try {
                    p.setLength(b.length);
                    timing.receive(p);
                    if (p.getLength() < 32 || (b[1] & 0x7f) != 0x52) continue;
                    byte[] r = new byte[32];
                    r[0] = (byte) 0x80; r[1] = (byte) 0xd3; r[3] = 7;
                    System.arraycopy(b, 24, r, 8, 8);    // originate = leur heure d'emission
                    ntp(r, 16); ntp(r, 24);
                    timing.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
                } catch (IOException e) {
                    return;
                }
            }
        }

        // ---- lecture

        void play() {
            int ch = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(RATE, ch, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t;
            try {
                t = new AudioTrack(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                        new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(ch)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                        Math.max(min, RATE / 5 * 2 * channels), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            } catch (Exception e) { Log.e(TAG, "AudioTrack", e); return; }
            track = t;
            applyVolume();
            t.play();
            if (Build.VERSION.SDK_INT >= 23) Log.i(TAG, "lecture demarree, tampon " + t.getBufferSizeInFrames() + " trames");
            long written = 0, lastStat = 0;
            int nSilence = 0, nSkip = 0, nMissing = 0;
            AudioTimestamp at = new AudioTimestamp();
            short[] silence = new short[frameLen * channels];
            try {
                while (active) {
                    short[] frame = null;
                    int n = frameLen;
                    long ts;
                    long dueNs;
                    boolean missing = false;
                    synchronized (lock) {
                        if (!started || !anchored) { lock.wait(50); continue; }
                        int s = nextSeq & MASK;
                        if (pcm[s] != null && slotSeq[s] == nextSeq) {
                            frame = pcm[s]; n = slotLen[s]; ts = slotTs[s];
                        } else if (seqDiff(latestSeq, nextSeq) < 0) {
                            lock.wait(20);                                         // rien de nouveau : on attend
                            continue;
                        } else {
                            missing = true; ts = nextTs;
                        }
                        dueNs = anchorNs + (long) (int) (ts - anchorRtp) * 1_000_000_000L / RATE;
                    }
                    long outNs = presentation(t, at, written);
                    long err = outNs - dueNs;
                    if (System.nanoTime() - lastStat > 2_000_000_000L) {
                        lastStat = System.nanoTime();
                        Log.d(TAG, "lecteur ts=" + ts + " ecart=" + err / 1_000_000 + "ms ecrites=" + written
                                + " silences=" + nSilence + " sauts=" + nSkip + " manquantes=" + nMissing);
                    }
                    if (missing && System.nanoTime() < dueNs - 60_000_000L) {      // laisse une chance au renvoi
                        synchronized (lock) { lock.wait(5); }
                        continue;
                    }
                    if (err < -DEADBAND_NS) {                                        // en avance : silence
                        int fr = (int) Math.min(frameLen, (-err) * RATE / 1_000_000_000L);
                        if (fr <= 0) fr = 1;
                        written += write(t, silence, fr);
                        nSilence++;
                        continue;
                    }
                    synchronized (lock) {
                        int s = nextSeq & MASK;
                        if (pcm[s] == frame) pcm[s] = null;
                        nextSeq = (nextSeq + 1) & 0xffff;
                        nextTs = (ts + n) & 0xffffffffL;
                    }
                    if (missing) nMissing++;
                    if (err > DEADBAND_NS && err < 3_000_000_000L) { nSkip++; continue; }   // en retard : on saute la trame
                    written += write(t, missing ? silence : frame, n);
                }
            } catch (InterruptedException ignored) {
            } finally {
                track = null;
                try { t.pause(); t.flush(); t.release(); } catch (Exception ignored) { }
            }
        }

        int write(AudioTrack t, short[] d, int frames) {
            int done = 0, total = frames * channels;
            while (done < total && active) {
                int w = t.write(d, done, total - done);
                if (w <= 0) break;
                done += w;
            }
            return done / channels;
        }

        /** heure (nanoTime) a laquelle la prochaine trame ecrite sortira */
        long presentation(AudioTrack t, AudioTimestamp at, long written) {
            if (t.getTimestamp(at) && at.framePosition > 0)
                return at.nanoTime + (written - at.framePosition) * 1_000_000_000L / RATE;
            long head = t.getPlaybackHeadPosition() & 0xffffffffL;
            return System.nanoTime() + (written - head) * 1_000_000_000L / RATE + 60_000_000L;
        }
    }

    static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    static void ntp(byte[] r, int o) {
        long ms = System.currentTimeMillis();
        long sec = ms / 1000 + 2208988800L;
        long frac = ((ms % 1000) << 32) / 1000;
        for (int i = 0; i < 4; i++) r[o + i] = (byte) (sec >>> (24 - 8 * i));
        for (int i = 0; i < 4; i++) r[o + 4 + i] = (byte) (frac >>> (24 - 8 * i));
    }

}
