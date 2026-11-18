package tv.zenhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serveur Companion Link : la TV se fait passer pour une Apple TV aupres de la telecommande du centre de controle
 * de l'iPhone ("Apple TV Remote"). L'iPhone la voit sous le nom "Chambre Franklin", s'appaire avec un code a
 * 4 chiffres affiche sur la TV, puis envoie boutons, gestes du pave tactile, volume, media et texte.
 *
 * Portage Java de atvr4samsung (github.com/vb3/atvr4samsung, src/atvr4samsung/companion/), MIT :
 *   Copyright (c) 2026 vb3
 * dont la base protocole vient de pyatv (github.com/postlund/pyatv), MIT :
 *   Copyright (c) 2020 Pierre Stahl
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions: The above
 * copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
 * SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 * Protocole :
 * - annonce Bonjour _companion-link._tcp par NsdManager, TXT d'une Apple TV 4K (discovery.py)
 * - TCP, port 49153 (ou suivant si occupe) ; trames [type, longueur 24 bits] + OPACK (Opack.java)
 * - pair-setup HAP : SRP-6a 3072 bits SHA-512 (classe Srp), code tire au hasard a chaque demande et montre sur
 *   la TV ; la cle Ed25519 de l'iPhone est gardee (8 iPhones au plus)
 * - pair-verify : X25519 ephemere, signatures Ed25519, puis ChaCha20-Poly1305 dans chaque sens avec un compteur
 *   (cles HKDF-SHA512 "ServerEncrypt-main" / "ClientEncrypt-main") ; seuls les iPhones appaires passent
 * - messages : voir dispatch() ; les actions partent vers RemoteActions sur un fil a part
 * Garde-fous repris de guardrails.py : 16 connexions dont 8 non authentifiees, 15 s pour s'authentifier,
 * 3 trames illisibles au plus, demandes d'appairage et echecs limites par adresse et au total sur 60 s.
 *
 * Crypto : Crypto.java (OpenSSL dans libzenairplay.so). Sans elle, start() ne fait rien.
 * Identite et iPhones appaires : SharedPreferences "companion" (id, seed, client.<id>, seen.<id>).
 * Cycle de vie comme Mirror : start() au demarrage, reannounce() a l'allumage de l'ecran, stop().
 * Appels utiles a l'integration : keyboard() quand un champ de texte prend ou perd le focus, systemStatus() quand
 * l'ecran s'allume ou s'eteint, forgetClients() pour oublier tous les iPhones.
 */
final class Companion {

    static final String TAG = "ZenCompanion";
    static final int PORT = 49153;
    static final String SERVICE = "_companion-link._tcp";
    static final String MODEL = "AppleTV14,1";   // Apple TV 4K (3e gen.) : iOS montre alors le bouton marche/arret

    // guardrails.py
    static final int MAX_CONNECTIONS = 16, MAX_UNAUTHENTICATED = 8, MALFORMED_FRAME_LIMIT = 3;
    static final long AUTH_TIMEOUT_MS = 15_000;
    static final long PIN_ENTRY_TIMEOUT_MS = 120_000;   // le temps de lire le code sur la TV et de le taper
    static final int SETUP_PER_SOURCE = 5, SETUP_GLOBAL = 20, FAILURES_PER_SOURCE = 5, FAILURES_GLOBAL = 20;
    static final long LIMIT_WINDOW_MS = 60_000;
    // mauvais codes depuis le dernier appairage reussi (prefs, survit au redemarrage) : au-dela, plus d'appairage
    // jusqu'a forgetClients() ou un appairage reussi. 4 chiffres : 100 essais = 1 % de chances, comme HAP.
    static final int PAIR_FAILURE_LIMIT = 100;
    static final int MAX_PAYLOAD = 1 << 20;   // framing.py : 1 Mio + 16 octets d'etiquette AEAD
    static final int MAX_CLIENTS = 8;
    static final int RTI_MAX_TEXT = 1024;

    // types de trame (enums.py FrameType)
    static final int F_NOOP = 1, F_PS_START = 3, F_PS_NEXT = 4, F_PV_START = 5, F_PV_NEXT = 6, F_E_OPACK = 8;
    static final int[] FRAME_TYPES = {0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 16, 17, 18, 32, 33, 34};

    // MediaControlCommand
    static final int MCC_PLAY = 1, MCC_PAUSE = 2, MCC_NEXT = 3, MCC_PREVIOUS = 4, MCC_GET_VOLUME = 5,
            MCC_SET_VOLUME = 6, MCC_SKIP_BY = 7, MCC_GET_CAPTION_SETTINGS = 12;
    static final long MEDIA_FLAGS = 0x100;   // MediaControlFlags.Volume : iOS active volume et sourdine
    static final long SYSTEM_AWAKE = 3, SYSTEM_ASLEEP = 1;
    static final int FOCUS_UNFOCUSED = 1, FOCUS_FOCUSED = 2;

    static Context ctx;
    static SharedPreferences prefs;
    static String name;
    static int port;
    static ServerSocket server;
    static NsdManager nsd;
    static NsdManager.RegistrationListener reg;
    static String serverId;
    static byte[] seed, serverPub;
    static final LinkedHashMap<String, byte[]> clients = new LinkedHashMap<>();   // iPhones appaires : id -> cle Ed25519
    static final ArrayList<Conn> conns = new ArrayList<>();
    static int unauthenticated;
    static final Limiter setupAttempts = new Limiter(SETUP_PER_SOURCE, SETUP_GLOBAL);
    static final Limiter failures = new Limiter(FAILURES_PER_SOURCE, FAILURES_GLOBAL);
    static double volume = 10.0;   // pourcentage, si RemoteActions.currentVolume() ne sait pas
    static Conn pinOwner;
    static final SecureRandom random = new SecureRandom();

    static final ThreadPoolExecutor actions = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(64), daemon("companion-actions"));
    static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("companion-timer"));

    // ------------------------------------------------------------------ cycle de vie

    static synchronized void start(Context c) {
        if (port > 0) { reannounce(); return; }
        if (!Crypto.load()) { Log.w(TAG, "Crypto indisponible : telecommande iPhone desactivee"); return; }
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE);
        // meme nom que l'AirPlay (AirPlay.namePref) : un seul appareil "Chambre Franklin" cote iPhone
        name = ctx.getSharedPreferences("airplay", Context.MODE_PRIVATE).getString("name", "Chambre Franklin");
        try {
            loadIdentity();
        } catch (RuntimeException e) {
            Log.e(TAG, "identite", e);
            return;
        }
        final ServerSocket s = bind();
        if (s == null) return;
        server = s;
        port = s.getLocalPort();
        Thread t = new Thread(new Runnable() { public void run() { acceptLoop(s); } }, "companion-accept");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "Companion sur le port " + port + ", " + clients.size() + " iPhone(s) appaire(s)");
        register();
    }

    static synchronized void stop() {
        unregister();
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        server = null;
        port = 0;
        for (Conn k : snapshot()) k.close();
    }

    static synchronized boolean running() { return port > 0; }

    static synchronized void reannounce() { unregister(); register(); }

    static ServerSocket bind() {
        for (int p = PORT; p <= PORT + 8; p++) {
            int want = p <= PORT + 7 ? p : 0;   // dernier essai : port libre au hasard
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(want), 16);
                return s;
            } catch (IOException e) {
                Log.w(TAG, "port " + want + " occupe");
            }
        }
        Log.e(TAG, "aucun port libre");
        return null;
    }

    static void acceptLoop(ServerSocket s) {
        while (true) {
            Socket k;
            try {
                k = s.accept();
            } catch (IOException e) {
                if (s.isClosed()) return;
                Log.w(TAG, "accept", e);
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
                continue;
            }
            // TV en veille rapide : le paquet de l'iPhone a reveille la puce pour ~15 s ; on la garde le temps du pair-verify
            RemoteActions.onAccept();
            Conn c = new Conn(k);
            String refus = null;
            synchronized (Companion.class) {
                if (conns.size() >= MAX_CONNECTIONS) refus = "trop de connexions";
                else if (unauthenticated >= MAX_UNAUTHENTICATED) refus = "trop de connexions non authentifiees";
                else { conns.add(c); unauthenticated++; c.unauth = true; }
            }
            if (refus != null) {
                Log.w(TAG, "connexion refusee (" + refus + ")");
                try { k.close(); } catch (IOException ignored) { }
                continue;
            }
            Thread t = new Thread(c, "companion-" + c.source);
            t.setDaemon(true);
            t.start();
        }
    }

    static synchronized List<Conn> snapshot() { return new ArrayList<>(conns); }

    // ------------------------------------------------------------------ Bonjour

    static synchronized void register() {
        if (port <= 0 || reg != null) return;
        if (nsd == null) nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo si = new NsdServiceInfo();
        si.setServiceName(name);
        si.setServiceType(SERVICE);
        si.setPort(port);
        for (Map.Entry<String, String> e : txt().entrySet()) si.setAttribute(e.getKey(), e.getValue());
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "annonce : " + i.getServiceName() + " " + i.getServiceType()); }
            public void onRegistrationFailed(NsdServiceInfo i, int err) {
                Log.w(TAG, "annonce refusee " + err);
                synchronized (Companion.class) { if (reg == this) reg = null; }
            }
            public void onServiceUnregistered(NsdServiceInfo i) { }
            public void onUnregistrationFailed(NsdServiceInfo i, int err) { }
        };
        try { nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, l); reg = l; }
        catch (Exception e) { Log.w(TAG, "annonce", e); }
    }

    static synchronized void unregister() {
        if (reg != null) try { nsd.unregisterService(reg); } catch (Exception ignored) { }
        reg = null;
    }

    /**
     * TXT d'une Apple TV (discovery.py) : iOS lit rpMd (modele) et rpFl (fonctions ; le bit 8 = MediaControl ouvre
     * volume et sourdine). Les identifiants derivent de l'identite du serveur : stables tant qu'elle ne change pas.
     */
    static LinkedHashMap<String, String> txt() {
        byte[] ba = txtId("rpBA");
        ba[0] = (byte) ((ba[0] | 0x02) & 0xFE);
        StringBuilder bt = new StringBuilder();
        for (int i = 0; i < ba.length; i++) bt.append(i > 0 ? ":" : "").append(String.format(Locale.ROOT, "%02X", ba[i] & 0xff));
        LinkedHashMap<String, String> t = new LinkedHashMap<>();
        t.put("rpMac", "1");
        t.put("rpHA", hex(txtId("rpHA")));
        t.put("rpHN", hex(txtId("rpHN")));
        t.put("rpVr", "715.2");
        t.put("rpMd", MODEL);
        t.put("rpFl", "0x36782");
        t.put("rpAD", hex(txtId("rpAD")));
        t.put("rpHI", hex(txtId("rpHI")));
        t.put("rpBA", bt.toString());
        t.put("rpMRtID", serverId);
        return t;
    }

    /** Meme derivation que discovery.py : 6 premiers octets de SHA-256(etiquette, champ, identifiant). */
    static byte[] txtId(String field) {
        byte[] h = sha("SHA-256", ("atvr4samsung-companion-txt-v1\0" + field + "\0" + serverId).getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(h, 6);
    }

    // ------------------------------------------------------------------ identite et iPhones appaires

    static synchronized void loadIdentity() {
        String id = prefs.getString("id", null), s = prefs.getString("seed", null);
        byte[] k = s != null && s.length() == 64 ? unhex(s) : null;
        if (id == null || id.isEmpty() || k == null) {
            id = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            k = Crypto.ed25519Keypair()[0];
            prefs.edit().putString("id", id).putString("seed", hex(k)).commit();
            Log.i(TAG, "nouvelle identite Companion");
        }
        serverId = id;
        seed = k;
        serverPub = Crypto.ed25519Public(k);
        clients.clear();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith("client.") || !(e.getValue() instanceof String)) continue;
            String v = (String) e.getValue();
            if (v.length() == 64) clients.put(e.getKey().substring(7), unhex(v));
        }
    }

    /** Garde la cle d'un iPhone ; plein (8), on oublie celui qu'on n'a pas vu depuis le plus longtemps. */
    static synchronized void addClient(String id, byte[] ltpk) {
        if (!clients.containsKey(id) && clients.size() >= MAX_CLIENTS) {
            String oldest = null;
            long t = Long.MAX_VALUE;
            for (String k : clients.keySet()) {
                long s = prefs.getLong("seen." + k, 0);
                if (s < t) { t = s; oldest = k; }
            }
            if (oldest != null) {
                clients.remove(oldest);
                prefs.edit().remove("client." + oldest).remove("seen." + oldest).commit();
                Log.i(TAG, "9e iPhone : le plus ancien est oublie");
            }
        }
        clients.put(id, ltpk);
        prefs.edit().putString("client." + id, hex(ltpk)).putLong("seen." + id, System.currentTimeMillis())
                .remove("pairFailures").commit();
        Log.i(TAG, "iPhone appaire (" + clients.size() + " en tout)");
    }

    static synchronized byte[] clientKey(String id) { return id == null ? null : clients.get(id); }

    static synchronized void clientSeen(String id) {
        if (prefs != null && clients.containsKey(id)) prefs.edit().putLong("seen." + id, System.currentTimeMillis()).apply();
    }

    static synchronized boolean pairLocked() {
        return prefs != null && prefs.getInt("pairFailures", 0) >= PAIR_FAILURE_LIMIT;
    }

    static synchronized void pairFailed() {
        if (prefs != null) prefs.edit().putInt("pairFailures", prefs.getInt("pairFailures", 0) + 1).commit();
    }

    /** Oublie un seul iPhone (identifiant de pair-setup M5) ; false s'il n'etait pas appaire. */
    static synchronized boolean forgetClient(String id) {
        if (prefs == null || id == null || clients.remove(id) == null) return false;
        prefs.edit().remove("client." + id).remove("seen." + id).commit();
        Log.i(TAG, "iPhone oublie (" + clients.size() + " restant(s))");
        return true;
    }

    /** Oublie tous les iPhones ; leurs connexions se ferment a la trame suivante. */
    static synchronized void forgetClients() {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        for (String k : clients.keySet()) e.remove("client." + k).remove("seen." + k);
        e.remove("pairFailures");   // rearme l'appairage bloque par PAIR_FAILURE_LIMIT
        e.commit();
        clients.clear();
        Log.i(TAG, "iPhones appaires oublies");
    }

    // ------------------------------------------------------------------ appels de l'integration

    static volatile String fieldText;   // texte du champ qui a le focus sur la TV, null sans focus

    /** keyboard(focused, "") : champ vide a l'ouverture. */
    static void keyboard(boolean focused) { keyboard(focused, ""); }

    /**
     * Un champ de texte prend (true) ou perd (false) le focus sur la TV : le clavier de l'iPhone s'ouvre ou se
     * ferme (_tiStarted / _tiStopped, make_ime_focus_handler). text = contenu actuel du champ, que l'iPhone
     * complete ; un iPhone qui ouvre sa session de texte pendant le focus recoit le clavier tout de suite.
     */
    static void keyboard(boolean focused, String text) {
        fieldText = focused ? (text != null ? text : "") : null;
        for (Conn k : snapshot()) k.setFocus(focused);
    }

    /** Ecran allume ou eteint : pousse SystemStatus (Awake / Asleep) aux iPhones connectes. */
    static void systemStatus(boolean awake) {
        for (Conn k : snapshot()) if (k.encrypted()) k.event("SystemStatus", 1234L, Opack.map("state", awake ? SYSTEM_AWAKE : SYSTEM_ASLEEP));
    }

    // ------------------------------------------------------------------ une connexion

    static final class Conn implements Runnable {
        static final int IDLE = 0, SETUP_M3 = 1, SETUP_M5 = 2, SETUP_COMPLETE = 3, VERIFY_M3 = 4, ENCRYPTED = 5, FAILED = 6;

        final Socket sock;
        final String source;
        InputStream in;
        OutputStream out;
        final Object writeLock = new Object();
        volatile boolean closed;
        boolean unauth;   // compte dans les connexions non authentifiees (garde par Companion.class)
        long deadline;
        int malformed;
        volatile int gen;   // les actions en file d'une generation passee sont abandonnees

        // appairage (auth.py)
        int phase = IDLE;
        Srp srp;
        boolean proofOk, pinShown;
        byte[] pvSession, pvServerPub, pvClientPub, outKey, inKey;
        String verifiedId;
        byte[] verifiedLtpk;
        byte[] encOut, encIn;
        long outCounter, inCounter;

        // etat de session (FakeCompanionSessionState)
        long sid;
        String serviceType, tvrcVersion;
        Object systemInfo;
        final HashSet<String> interests = new HashSet<>();
        final HashSet<Long> pressed = new HashSet<>();
        final Object rtiLock = new Object();
        boolean rtiRegistered;
        int rtiFocus = FOCUS_UNFOCUSED;
        String rtiText = "";
        byte[] rtiUuid;
        double touchWidth, touchHeight;
        final Touch touch = new Touch(this);
        long lastSelect = -1_000_000;
        boolean selectSwallowed;

        Conn(Socket s) {
            sock = s;
            source = s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : "?";
        }

        boolean encrypted() { return encIn != null && !closed; }

        public void run() {
            Log.i(TAG, "connexion de " + source);
            try {
                sock.setTcpNoDelay(true);
                sock.setKeepAlive(true);   // iPhone parti sans FIN : la connexion finit par tomber et libere sa place
                in = new BufferedInputStream(sock.getInputStream());
                out = sock.getOutputStream();
                deadline = now() + AUTH_TIMEOUT_MS;
                byte[] h = new byte[4];
                while (!closed) {
                    if (encIn == null) {
                        long left = deadline - now();
                        if (left <= 0) { Log.w(TAG, "delai d'authentification depasse, connexion fermee"); break; }
                        sock.setSoTimeout((int) left);
                    } else sock.setSoTimeout(0);
                    if (!readFully(h, true)) break;
                    int n = ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
                    if (n > MAX_PAYLOAD + 16) { Log.w(TAG, "trame annoncee trop grande, connexion fermee"); break; }
                    byte[] p = new byte[n];
                    readFully(p, false);
                    if (!handleFrame(h, p)) break;
                }
            } catch (SocketTimeoutException e) {
                Log.w(TAG, "delai d'authentification depasse, connexion fermee");
            } catch (IOException e) {
                if (!closed) Log.i(TAG, "connexion " + source + " : " + e.getMessage());
            } catch (RuntimeException e) {
                Log.w(TAG, "connexion " + source, e);
            } finally {
                close();
            }
        }

        boolean readFully(byte[] b, boolean eofOk) throws IOException {
            int o = 0;
            while (o < b.length) {
                int r = in.read(b, o, b.length - o);
                if (r < 0) {
                    if (eofOk && o == 0) return false;
                    throw new EOFException("fin de flux");
                }
                o += r;
            }
            return true;
        }

        void close() {
            boolean wasEncrypted;
            synchronized (this) {
                if (closed) return;
                closed = true;
                gen++;
                wasEncrypted = encIn != null;
            }
            try { sock.close(); } catch (IOException ignored) { }
            touch.stopRepeat();
            releasePressed();
            clearSetup();
            synchronized (Companion.class) {
                conns.remove(this);
                if (unauth) { unauth = false; unauthenticated--; }
            }
            if (wasEncrypted) post(new Runnable() { public void run() { RemoteActions.onConnection(false); } });
            Log.i(TAG, "deconnexion de " + source);
        }

        // ---------------------------------------------------------- trames

        boolean handleFrame(byte[] header, byte[] data) {
            int type = header[0] & 0xff;
            boolean known = false;
            for (int t : FRAME_TYPES) if (t == type) known = true;
            if (!known) return malformed("type de trame inconnu " + type);
            boolean auth = type == F_PS_START || type == F_PS_NEXT || type == F_PV_START || type == F_PV_NEXT;
            if (auth && (encIn != null || phase == ENCRYPTED)) {
                // une trame d'appairage en clair apres le chiffrement rejouerait la derivation des cles
                Log.w(TAG, "trame d'appairage apres le chiffrement, connexion fermee");
                return false;
            }
            if (data.length > MAX_PAYLOAD && (encIn == null || auth)) { Log.w(TAG, "trame trop grande"); return false; }
            if (encIn != null && !auth) {
                if (!clientAuthorized()) { Log.w(TAG, "iPhone plus autorise, connexion fermee"); return false; }
                // watchOS envoie ce maintien de connexion vide hors chiffrement : le dechiffrer decalerait le compteur
                if (type == F_NOOP && data.length == 0) return true;
                byte[] plain = Crypto.chachaOpen(encIn, nonce(inCounter++), header, data);
                if (plain == null) {
                    // un echec desynchronise les compteurs pour de bon ; iOS se reconnecte avec de nouvelles cles
                    Log.w(TAG, "dechiffrement impossible, connexion fermee");
                    return false;
                }
                data = plain;
            }
            if (data.length > MAX_PAYLOAD) { Log.w(TAG, "trame dechiffree trop grande"); return false; }
            Object msg;
            try { msg = Opack.unpack(data); }
            catch (RuntimeException e) { return malformed("OPACK illisible"); }
            try {
                if (auth) return authFrame(type, msg) && !closed;
                if (encIn == null) return malformed("message avant authentification");
                if (msg instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) msg;
                    // watchOS repond a nos messages par le seul _x : ce n'est pas une commande
                    if (!m.containsKey("_i") && m.get("_t") instanceof Long && (Long) m.get("_t") == 3 && m.get("_x") instanceof Long)
                        return !closed;
                }
                if (TRACE) Log.d(TAG, "<- " + source + " " + trace(msg));
                dispatch((Map<?, ?>) msg);
            } catch (RuntimeException e) {
                Log.d(TAG, "message invalide", e);
                return malformed("message Companion invalide");
            }
            return !closed;
        }

        boolean malformed(String why) {
            malformed++;
            if (malformed >= MALFORMED_FRAME_LIMIT) {
                Log.w(TAG, "trame illisible (" + malformed + "/" + MALFORMED_FRAME_LIMIT + " : " + why + "), connexion fermee");
                return false;
            }
            Log.w(TAG, "trame illisible (" + malformed + "/" + MALFORMED_FRAME_LIMIT + " : " + why + "), toleree");
            return true;
        }

        void send(int type, Object data) {
            if (TRACE && encOut != null) Log.d(TAG, "-> " + source + " " + trace(data));
            byte[] enc = Opack.pack(data);
            if (enc.length > MAX_PAYLOAD) { Log.w(TAG, "trame sortante trop grande, abandonnee"); return; }
            synchronized (writeLock) {
                if (closed) return;
                int n = enc.length + (encOut != null ? 16 : 0);
                byte[] h = {(byte) type, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
                if (encOut != null) enc = Crypto.chachaSeal(encOut, nonce(outCounter++), h, enc);
                byte[] f = new byte[4 + enc.length];
                System.arraycopy(h, 0, f, 0, 4);
                System.arraycopy(enc, 0, f, 4, enc.length);
                try {
                    out.write(f);
                    out.flush();
                } catch (IOException e) {
                    Log.i(TAG, "envoi vers " + source + " : " + e.getMessage());
                    try { sock.close(); } catch (IOException ignored) { }
                }
            }
        }

        void respond(Map<?, ?> req, Object content) {
            send(F_E_OPACK, Opack.map("_i", req.get("_i"), "_x", xid(req), "_t", 3L, "_c", content));
        }

        void event(String id, Object xid, Object content) {
            send(F_E_OPACK, Opack.map("_i", id, "_x", xid, "_t", 1L, "_c", content));
        }

        void error(Map<?, ?> req, String message, long code) {
            send(F_E_OPACK, Opack.map("_i", req.get("_i"), "_x", xid(req), "_t", 3L, "_ec", code, "_ed", "RPErrorDomain", "_em", message));
        }

        static Object xid(Map<?, ?> req) {
            if (!req.containsKey("_x")) throw new IllegalArgumentException("message sans _x");
            return req.get("_x");
        }

        /** Action vers RemoteActions, abandonnee si la connexion ou la session TV Remote se termine avant. */
        void act(final Runnable r) {
            final int g = gen;
            post(new Runnable() {
                public void run() {
                    if (closed || gen != g) return;
                    r.run();
                }
            });
        }

        // ---------------------------------------------------------- appairage HAP (auth.py)

        boolean authFrame(int type, Object msg) {
            if (phase == ENCRYPTED) return false;
            LinkedHashMap<Integer, byte[]> pd;
            int seq;
            try {
                Object blob = ((Map<?, ?>) msg).get("_pd");
                if (!(blob instanceof byte[])) throw new IllegalArgumentException("pas de _pd");
                pd = Opack.Tlv.read((byte[]) blob);
                byte[] s = pd.get(Opack.Tlv.SEQ_NO);
                if (s == null || s.length != 1) throw new IllegalArgumentException("pas de numero d'etape");
                seq = s[0] & 0xff;
            } catch (RuntimeException e) {
                return rejectTransition(type, -1);
            }
            int next;
            if (phase == IDLE && type == F_PS_START && seq == 1) next = SETUP_M3;
            else if (phase == IDLE && type == F_PV_START && seq == 1) next = VERIFY_M3;
            else if (phase == SETUP_M3 && type == F_PS_NEXT && seq == 3) next = SETUP_M5;
            else if (phase == SETUP_M5 && type == F_PS_NEXT && seq == 5) next = SETUP_COMPLETE;
            // iOS peut enchainer pair-verify sur la meme connexion apres pair-setup
            else if (phase == SETUP_COMPLETE && type == F_PV_START && seq == 1) next = VERIFY_M3;
            else if (phase == VERIFY_M3 && type == F_PV_NEXT && seq == 3) next = ENCRYPTED;
            // identite inconnue de l'iPhone : il essaie pair-verify, puis se rabat sur pair-setup sur la meme connexion
            else if (phase == VERIFY_M3 && type == F_PS_START && seq == 1) next = SETUP_M3;
            else return rejectTransition(type, seq);
            boolean ok;
            try {
                if (type == F_PS_START) ok = m1Setup();
                else if (type == F_PS_NEXT && seq == 3) ok = m3Setup(pd);
                else if (type == F_PS_NEXT) ok = m5Setup(pd);
                else if (type == F_PV_START) ok = m1Verify(pd);
                else ok = m3Verify(pd);
            } catch (RuntimeException e) {
                Log.w(TAG, "appairage : erreur, connexion fermee", e);
                return rejectTransition(type, seq);
            }
            if (!ok) {
                clearPending();
                phase = FAILED;
                return false;
            }
            phase = next;
            return true;
        }

        boolean rejectTransition(int type, int seq) {
            clearPending();
            phase = FAILED;
            if (type == F_PS_START || type == F_PS_NEXT) {
                failures.record(source);
                setupError(seq <= 1 ? 2 : seq <= 3 ? 4 : 6, Opack.Tlv.ERR_AUTHENTICATION);
            } else verifyError();
            return false;
        }

        boolean m1Verify(LinkedHashMap<Integer, byte[]> pd) {
            clearVerify();
            verifiedId = null;
            verifiedLtpk = null;
            byte[] outer;
            try {
                // X25519 ephemere a chaque verify : une cle fixe permettrait de rejouer une session enregistree
                byte[][] kp = Crypto.x25519Keypair();
                byte[] spub = kp[1], cpub = pd.get(Opack.Tlv.PUBLIC_KEY);
                if (cpub == null || cpub.length != 32) throw new IllegalArgumentException("cle publique");
                byte[] shared = Crypto.x25519(kp[0], cpub);
                if (shared == null) throw new IllegalArgumentException("X25519");
                byte[] sk = hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info");
                byte[] id = serverId.getBytes(StandardCharsets.UTF_8);
                byte[] sig = Crypto.ed25519Sign(seed, concat(spub, id, cpub));
                byte[] inner = Opack.Tlv.write(Opack.Tlv.IDENTIFIER, id, Opack.Tlv.SIGNATURE, sig);
                byte[] enc = Crypto.chachaSeal(sk, fixedNonce("PV-Msg02"), new byte[0], inner);
                outer = Opack.Tlv.write(Opack.Tlv.SEQ_NO, new byte[]{2}, Opack.Tlv.PUBLIC_KEY, spub, Opack.Tlv.ENCRYPTED_DATA, enc);
                outKey = hkdf(shared, "", "ServerEncrypt-main");
                inKey = hkdf(shared, "", "ClientEncrypt-main");
                pvSession = sk;
                pvServerPub = spub;
                pvClientPub = cpub;
            } catch (RuntimeException e) {
                Log.w(TAG, "pair-verify M1 refuse (cle publique invalide)");
                clearVerify();
                verifyError();
                return false;
            }
            send(F_PV_NEXT, Opack.map("_pd", outer));
            return true;
        }

        /** M3 de pair-verify : seul un iPhone appaire, avec une signature valable, passe. */
        boolean verifyClient(LinkedHashMap<Integer, byte[]> pd) {
            try {
                byte[] plain = Crypto.chachaOpen(pvSession, fixedNonce("PV-Msg03"), new byte[0], pd.get(Opack.Tlv.ENCRYPTED_DATA));
                if (plain == null) throw new IllegalArgumentException("dechiffrement");
                LinkedHashMap<Integer, byte[]> sub = Opack.Tlv.read(plain);
                byte[] id = sub.get(Opack.Tlv.IDENTIFIER), sig = sub.get(Opack.Tlv.SIGNATURE);
                String ids = utf8Strict(id);
                byte[] ltpk = clientKey(ids);
                if (ltpk == null) { Log.w(TAG, "pair-verify d'un iPhone inconnu, refuse"); return false; }
                if (sig == null || !Crypto.ed25519Verify(ltpk, concat(pvClientPub, id, pvServerPub), sig))
                    throw new IllegalArgumentException("signature");
                verifiedId = ids;
                verifiedLtpk = ltpk;
                clientSeen(ids);
                Log.i(TAG, "pair-verify OK");
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "pair-verify refuse (preuve invalide)");
                verifiedId = null;
                verifiedLtpk = null;
                return false;
            }
        }

        boolean m3Verify(LinkedHashMap<Integer, byte[]> pd) {
            if (pvSession == null || !verifyClient(pd)) {
                verifyError();
                clearVerify();
                return false;
            }
            byte[] o = outKey, i = inKey;
            if (o == null || i == null) {
                verifiedId = null;
                verifiedLtpk = null;
                clearVerify();
                verifyError();
                return false;
            }
            send(F_PV_NEXT, Opack.map("_pd", Opack.Tlv.write(Opack.Tlv.SEQ_NO, new byte[]{4})));
            synchronized (writeLock) {
                encOut = o;
                encIn = i;
                outCounter = 0;
                inCounter = 0;
            }
            synchronized (Companion.class) { if (unauth) { unauth = false; unauthenticated--; } }
            clearVerify();
            post(new Runnable() { public void run() { RemoteActions.onConnection(true); } });
            return true;
        }

        boolean m1Setup() {
            // un setup qui suit un verify abandonne ne garde rien de ses cles
            clearVerify();
            verifiedId = null;
            verifiedLtpk = null;
            int[] admit = setupAttempts.admit(source);
            if (admit != null) {
                clearSetup();
                setupError(2, admit[0]);
                return false;
            }
            int retry = failures.check(source);
            if (retry > 0) {
                clearSetup();
                backoff(retry);
                return false;
            }
            if (pairLocked()) {
                // ni code ni ecran allume : la TV ne repond plus aux demandes d'appairage
                Log.w(TAG, "appairage refuse : " + PAIR_FAILURE_LIMIT + " mauvais codes, voir FORGET_IPHONES");
                clearSetup();
                setupError(2, Opack.Tlv.ERR_MAX_TRIES);
                return false;
            }
            if (srp != null) {
                failures.record(source);
                clearSetup();
                retry = failures.check(source);
                if (retry > 0) { backoff(retry); return false; }
            }
            String pin = newPin();
            srp = new Srp(pin);
            proofOk = false;
            byte[] tlv = Opack.Tlv.write(Opack.Tlv.SEQ_NO, new byte[]{2}, Opack.Tlv.SALT, srp.salt,
                    Opack.Tlv.PUBLIC_KEY, srp.publicKey(), 27, new byte[]{1});
            send(F_PS_NEXT, Opack.map("_pd", tlv, "_pwTy", 1L));
            showPin(pin);
            deadline = now() + PIN_ENTRY_TIMEOUT_MS;
            Log.i(TAG, "appairage demande par " + source + ", code affiche");
            return true;
        }

        boolean m3Setup(LinkedHashMap<Integer, byte[]> pd) {
            Srp s = srp;
            if (s == null || proofOk) {
                failures.record(source);
                clearSetup();
                setupError(4, Opack.Tlv.ERR_AUTHENTICATION);
                return false;
            }
            boolean ok;
            try {
                ok = s.verify(pd.get(Opack.Tlv.PUBLIC_KEY), pd.get(Opack.Tlv.PROOF));
            } catch (RuntimeException e) {
                failures.record(source);
                clearSetup();
                setupError(4, Opack.Tlv.ERR_AUTHENTICATION);
                return false;
            }
            byte[] tlv;
            if (ok) {
                proofOk = true;
                tlv = Opack.Tlv.write(Opack.Tlv.PROOF, s.serverProof, Opack.Tlv.SEQ_NO, new byte[]{4});
            } else {
                Log.w(TAG, "appairage : mauvais code");
                failures.record(source);
                pairFailed();
                clearSetup();
                tlv = Opack.Tlv.write(Opack.Tlv.ERROR, new byte[]{Opack.Tlv.ERR_AUTHENTICATION}, Opack.Tlv.SEQ_NO, new byte[]{4});
            }
            send(F_PS_NEXT, Opack.map("_pd", tlv));
            return ok;
        }

        boolean m5Setup(LinkedHashMap<Integer, byte[]> pd) {
            Srp s = srp;
            if (s == null || !proofOk) {
                failures.record(source);
                clearSetup();
                setupError(6, Opack.Tlv.ERR_AUTHENTICATION);
                return false;
            }
            byte[] sk = hkdf(s.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info");
            LinkedHashMap<Integer, byte[]> c;
            try {
                byte[] plain = Crypto.chachaOpen(sk, fixedNonce("PS-Msg05"), new byte[0], pd.get(Opack.Tlv.ENCRYPTED_DATA));
                if (plain == null) throw new IllegalArgumentException("dechiffrement");
                c = Opack.Tlv.read(plain);
            } catch (RuntimeException e) {
                Log.w(TAG, "pair-setup M5 illisible, refuse");
                failures.record(source);
                clearSetup();
                setupError(6, Opack.Tlv.ERR_AUTHENTICATION);
                return false;
            }
            int err = registerClient(c, s.key);
            if (err != 0) {
                if (err == Opack.Tlv.ERR_AUTHENTICATION) failures.record(source);
                clearSetup();
                setupError(6, err);
                return false;
            }
            byte[] ax = hkdf(s.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info");
            byte[] id = serverId.getBytes(StandardCharsets.UTF_8);
            Map<Object, Object> other = Opack.map(
                    "altIRK", unhex("2d54e07a882a656e11ab82762d2725c5"),
                    "accountID", "DC6A7CB6-CA1A-4BF4-880D-A61B717814DB",
                    "model", "AppleTV6,2",
                    "wifiMAC", unhex("40ffa18fa1b9"),
                    "name", name,
                    "mac", unhex("40c4ff8fb199"));
            byte[] sig = Crypto.ed25519Sign(seed, concat(ax, id, serverPub));
            byte[] tlv = Opack.Tlv.write(Opack.Tlv.IDENTIFIER, id, Opack.Tlv.PUBLIC_KEY, serverPub,
                    Opack.Tlv.SIGNATURE, sig, 17, Opack.pack(other));
            byte[] enc = Crypto.chachaSeal(sk, fixedNonce("PS-Msg06"), new byte[0], tlv);
            send(F_PS_NEXT, Opack.map("_pd", Opack.Tlv.write(Opack.Tlv.SEQ_NO, new byte[]{6}, Opack.Tlv.ENCRYPTED_DATA, enc)));
            clearSetup();
            Log.i(TAG, "appairage reussi avec " + source);
            return true;
        }

        /** Cle a long terme de l'iPhone (M5) : identifiant UTF-8, cle de 32 octets, signature sur iOSDeviceX. */
        int registerClient(LinkedHashMap<Integer, byte[]> c, byte[] key) {
            byte[] id = c.get(Opack.Tlv.IDENTIFIER), ltpk = c.get(Opack.Tlv.PUBLIC_KEY), sig = c.get(Opack.Tlv.SIGNATURE);
            if (id == null || ltpk == null || sig == null) { Log.w(TAG, "pair-setup M5 incomplet, refuse"); return Opack.Tlv.ERR_AUTHENTICATION; }
            if (ltpk.length != 32) { Log.w(TAG, "pair-setup M5 : cle de " + ltpk.length + " octets, refuse"); return Opack.Tlv.ERR_AUTHENTICATION; }
            String ids;
            try { ids = utf8Strict(id); } catch (RuntimeException e) { Log.w(TAG, "pair-setup M5 : identifiant non UTF-8"); return Opack.Tlv.ERR_AUTHENTICATION; }
            byte[] x = hkdf(key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info");
            if (!Crypto.ed25519Verify(ltpk, concat(x, id, ltpk), sig)) { Log.w(TAG, "pair-setup M5 : signature invalide"); return Opack.Tlv.ERR_AUTHENTICATION; }
            addClient(ids, ltpk);
            return 0;
        }

        boolean clientAuthorized() {
            byte[] k = clientKey(verifiedId);
            return k != null && verifiedLtpk != null && MessageDigest.isEqual(k, verifiedLtpk);
        }

        void showPin(final String pin) {
            synchronized (Companion.class) { pinOwner = this; }
            pinShown = true;
            post(new Runnable() { public void run() { RemoteActions.showPin(pin); } });
        }

        void clearSetup() {
            srp = null;
            proofOk = false;
            if (!pinShown) return;
            pinShown = false;
            synchronized (Companion.class) {
                if (pinOwner != this) return;   // une autre connexion affiche son propre code
                pinOwner = null;
            }
            post(new Runnable() { public void run() { RemoteActions.hidePin(); } });
        }

        void clearVerify() {
            outKey = null;
            inKey = null;
            pvSession = null;
            pvServerPub = null;
            pvClientPub = null;
        }

        void clearPending() {
            clearSetup();
            clearVerify();
            verifiedId = null;
            verifiedLtpk = null;
        }

        void verifyError() {
            send(F_PV_NEXT, Opack.map("_pd", Opack.Tlv.write(Opack.Tlv.SEQ_NO, new byte[]{4},
                    Opack.Tlv.ERROR, new byte[]{Opack.Tlv.ERR_AUTHENTICATION})));
        }

        void setupError(int seq, int error) {
            send(F_PS_NEXT, Opack.map("_pd", Opack.Tlv.write(Opack.Tlv.SEQ_NO, new byte[]{(byte) seq},
                    Opack.Tlv.ERROR, new byte[]{(byte) error})));
        }

        void backoff(int seconds) {
            send(F_PS_NEXT, Opack.map("_pd", Opack.Tlv.write(Opack.Tlv.SEQ_NO, new byte[]{2},
                    Opack.Tlv.ERROR, new byte[]{Opack.Tlv.ERR_BACK_OFF},
                    Opack.Tlv.BACK_OFF, new byte[]{(byte) seconds, (byte) (seconds >> 8)})));
        }

        // ---------------------------------------------------------- messages (appletv.py + server.py)

        void dispatch(Map<?, ?> m) {
            String i = ((String) m.get("_i")).toLowerCase(Locale.ROOT);
            switch (i) {
                case "_hidc": hidButton(m); break;
                case "_hidt": hidTouch(m); break;
                case "_touchstart": touchStart(m); break;
                case "_touchstop": touch.stopRepeat(); respond(m, Opack.map()); break;
                case "_mcc": legacyMediaControl(m); break;
                case "mediacontrolcommand": mediaControl(m); break;
                case "_sessionstart": {
                    Map<?, ?> c = content(m);
                    sid = num(c.get("_sid"));
                    serviceType = (String) c.get("_srvT");
                    respond(m, Opack.map("_sid", 5555L));
                    break;
                }
                case "_sessionstop":
                    if (num(content(m).get("_sid")) == ((5555L << 32) | sid)) { sid = 0; respond(m, Opack.map()); }
                    else error(m, "Invalid SID", 1337);
                    break;
                case "_systeminfo": {
                    systemInfo = m.get("_c");
                    Map<?, ?> c = content(m);
                    Log.i(TAG, "appareil : " + c.get("name") + " (" + c.get("model") + ")");
                    respond(m, Opack.map());
                    break;
                }
                case "tvrcsessionstart": {
                    // nouvelle session TV Remote : les actions en attente de la precedente sont abandonnees
                    touch.stopRepeat();
                    gen++;
                    releasePressed();
                    Object c = m.containsKey("_c") ? m.get("_c") : Opack.map();
                    if (c instanceof Map) {
                        Object v = ((Map<?, ?>) c).get("ProtocolVersionKey");
                        tvrcVersion = v instanceof String ? (String) v : null;
                    }
                    respond(m, c);
                    // iOS laisse ~300 ms pour annoncer le volume, sinon il garde volume et sourdine sur le telephone
                    event("MediaControlStatus", xid(m), Opack.map("MediaControlFlags", MEDIA_FLAGS));
                    event("_iMC", xid(m), Opack.map("_mcF", MEDIA_FLAGS));
                    Log.i(TAG, "session TV Remote ouverte");
                    break;
                }
                case "tvrcsessionstop":
                    touch.stopRepeat();
                    gen++;
                    releasePressed();
                    respond(m, Opack.map());
                    break;
                case "_interest": interest(m); break;
                case "_tistart": textStart(m); break;
                case "_tistop": textStop(m); break;
                case "_tic": textChange(m); break;
                case "fetchattentionstate":
                    respond(m, Opack.map("state", RemoteActions.screenOn() ? SYSTEM_AWAKE : SYSTEM_ASLEEP));
                    break;
                case "fetchmediacontrolstatus":
                    // iOS 26 lit "MediaControlFlags" (et pas "_mcF") pour activer volume et sourdine
                    respond(m, Opack.map("MediaControlFlags", MEDIA_FLAGS));
                    break;
                case "_launchapp": {
                    Map<?, ?> c = content(m);
                    final Object bundle = c.get("_bundleID"), url = c.get("_urlS");
                    if (bundle != null || url != null) act(new Runnable() {
                        public void run() { RemoteActions.launchApp(bundle != null ? String.valueOf(bundle) : null, bundle == null ? String.valueOf(url) : null); }
                    });
                    respond(m, Opack.map());
                    break;
                }
                case "fetchlaunchableapplicationsevent": {
                    LinkedHashMap<Object, Object> apps = new LinkedHashMap<>();
                    Map<String, String> a = RemoteActions.apps();
                    if (a != null) apps.putAll(a);
                    respond(m, apps);
                    break;
                }
                // demandes et notifications de la session iOS 26/27 : une Apple TV repond par un dictionnaire vide
                case "switchuseraccountevent":
                case "fetchuseraccountsevent":
                case "fetchsupportedactionsevent":
                case "publishpresenceevent":
                case "switchactiveuseraccountevent":
                case "fetchupnextinfoevent":
                case "fetchcurrenttopshelfitemsevent":
                case "fetchsiriremoteinfo":
                case "fetchcurrentnowplayinginfoevent":
                    respond(m, Opack.map());
                    break;
                default:
                    Log.w(TAG, "message sans traitement : " + m.get("_i"));
                    error(m, "No request handler", 58822);
            }
        }

        /** Boutons : 1 = appui, 2 = relachement (iOS), 0 = relachement sans appui (watchOS). */
        void hidButton(Map<?, ?> m) {
            long code, state;
            try {
                Map<?, ?> c = content(m);
                code = num(c.get("_hidC"));
                state = num(c.get("_hBtS"));
            } catch (RuntimeException e) {
                malformed("bouton HID illisible");
                return;
            }
            final int k = (int) code;
            if (state == 0) {
                boolean was = pressed.remove(code);
                respond(m, Opack.map());
                if (code != RemoteActions.SIRI) { if (was) buttonEdge(k, false); else buttonClick(k); }
                return;
            }
            if (code == RemoteActions.SIRI) {
                // pas de relais de la voix : on accuse reception et on oublie l'appui
                pressed.remove(code);
                respond(m, Opack.map());
                return;
            }
            if (state == 1) {
                pressed.add(code);
                respond(m, Opack.map());
                buttonEdge(k, true);
            } else if (state == 2) {
                if (code == RemoteActions.SLEEP || code == RemoteActions.WAKE) {
                    boolean was = pressed.remove(code);
                    respond(m, Opack.map());
                    if (was) buttonEdge(k, false); else buttonClick(k);
                    return;
                }
                if (!pressed.remove(code)) {
                    // comme le Python : erreur pour iOS, mais le bouton agit quand meme
                    Log.w(TAG, "bouton " + code + " relache sans appui");
                    error(m, "Missing button DOWN for " + code, 1337);
                    buttonClick(k);
                    return;
                }
                if (code == RemoteActions.VOLUME_UP) volumeChanged(volume + 5);
                else if (code == RemoteActions.VOLUME_DOWN) volumeChanged(volume - 5);
                respond(m, Opack.map());
                buttonEdge(k, false);
            } else Log.w(TAG, "bouton " + code + " etat " + state + " inconnu");
        }

        /** Le clic central arrive en double (bouton SELECT et tape) : un seul passe dans les 400 ms. */
        void buttonEdge(final int code, final boolean down) {
            if (code == RemoteActions.SELECT) {
                long t = now();
                if (down) {
                    selectSwallowed = t - lastSelect < Touch.SELECT_DEDUPE_MS;
                    if (selectSwallowed) { Log.d(TAG, "SELECT en double ignore"); return; }
                } else {
                    if (selectSwallowed) { selectSwallowed = false; return; }
                    lastSelect = t;
                }
            }
            act(new Runnable() { public void run() { RemoteActions.button(code, down); } });
        }

        void buttonClick(int code) { buttonEdge(code, true); buttonEdge(code, false); }

        /**
         * Connexion ou session finie doigt pose : le relachement n'arrivera jamais et act() abandonne ceux en file.
         * Sans cela le demon repete la touche HOLD_MAX_MS (volume + jusqu'au maximum). Hors act : jamais abandonne.
         */
        void releasePressed() {
            final ArrayList<Long> held;
            synchronized (pressed) { held = new ArrayList<>(pressed); pressed.clear(); }
            for (final Long code : held) {
                if (code == RemoteActions.SIRI) continue;
                post(new Runnable() { public void run() { RemoteActions.release((int) (long) code); } });
            }
        }

        void tap() {
            long t = now();
            if (t - lastSelect < Touch.SELECT_DEDUPE_MS || (pressed.contains((long) RemoteActions.SELECT) && !selectSwallowed)) {
                Log.d(TAG, "tape en double ignoree");
                return;
            }
            lastSelect = t;
            act(new Runnable() { public void run() { RemoteActions.tap(); } });
        }

        void swipe(final int direction) {
            act(new Runnable() { public void run() { RemoteActions.swipe(direction); } });
        }

        void hidTouch(Map<?, ?> m) {
            Map<?, ?> c = content(m);
            int phase = (int) num(c.get("_tPh"));
            // iOS 27 peut omettre l'horodatage, et les relachements leurs coordonnees : on garde alors le dernier point
            int x = c.get("_cx") != null ? (int) num(c.get("_cx")) : touch.lastX();
            int y = c.get("_cy") != null ? (int) num(c.get("_cy")) : touch.lastY();
            touch.onTouch(phase, x, y);
        }

        void touchStart(Map<?, ?> m) {
            Map<?, ?> c = content(m);
            Object w = c.get("_width"), h = c.get("_height");
            double wd = w instanceof Number ? ((Number) w).doubleValue() : 0, hd = h instanceof Number ? ((Number) h).doubleValue() : 0;
            if (!(wd > 0 && wd <= 1000 && hd > 0 && hd <= 1000)) { error(m, "Invalid touchpad width or height", 1337); return; }
            touchWidth = wd;
            touchHeight = hd;
            // iOS 26 exige un identifiant de pave tactile, sinon "No touch device ID" et la telecommande se ferme
            respond(m, Opack.map("_i", 1L));
        }

        void legacyMediaControl(Map<?, ?> m) {
            Map<?, ?> c = m.get("_c") instanceof Map ? (Map<?, ?>) m.get("_c") : Opack.map();
            long cmd;
            try { cmd = num(c.get("_mcc")); }
            catch (RuntimeException e) { malformed("commande media illisible"); return; }
            if (cmd == MCC_SET_VOLUME) {
                if (!(c.get("_vol") instanceof Number)) { malformed("niveau de volume illisible"); return; }
                setVolume(((Number) c.get("_vol")).doubleValue());
                respond(m, Opack.map());
                // comme la base pyatv (et une Apple TV) : le client relit le volume a cet evenement
                event("_iMC", 1234L, Opack.map("_mcF", MEDIA_FLAGS));
            } else if (cmd == MCC_GET_VOLUME) {
                respond(m, Opack.map("_vol", currentVolume()));
            } else if (cmd == MCC_SKIP_BY) {
                media(MCC_SKIP_BY, c.get("_skpS") instanceof Number ? ((Number) c.get("_skpS")).doubleValue() : 0);
                respond(m, Opack.map());
            } else if (cmd == MCC_PLAY || cmd == MCC_PAUSE || cmd == MCC_NEXT || cmd == MCC_PREVIOUS) {
                media((int) cmd, 0);
                respond(m, Opack.map());
            } else Log.w(TAG, "commande media " + cmd + " non geree");
        }

        /** Forme iOS 26 : _i = "MediaControlCommand", code sous _c.MediaControlCommand ; toujours une reponse. */
        void mediaControl(Map<?, ?> m) {
            Map<?, ?> c = m.get("_c") instanceof Map ? (Map<?, ?>) m.get("_c") : Opack.map();
            Object o = c.get("MediaControlCommand");
            long cmd = o instanceof Number ? ((Number) o).longValue() : -1;
            LinkedHashMap<Object, Object> args = new LinkedHashMap<>();
            if (cmd == MCC_GET_VOLUME) args.put("_vol", currentVolume());
            else if (cmd == MCC_SET_VOLUME) { if (c.get("_vol") instanceof Number) setVolume(((Number) c.get("_vol")).doubleValue()); }
            // iOS ferme la session si la reponse est vide : sous-titres desactives
            else if (cmd == MCC_GET_CAPTION_SETTINGS) args.put("_cse", false);
            else if (cmd == MCC_SKIP_BY) media(MCC_SKIP_BY, c.get("_skpS") instanceof Number ? ((Number) c.get("_skpS")).doubleValue() : 0);
            else if (cmd == MCC_PLAY || cmd == MCC_PAUSE || cmd == MCC_NEXT || cmd == MCC_PREVIOUS) media((int) cmd, 0);
            respond(m, args);
        }

        void media(final int cmd, final double seconds) {
            act(new Runnable() { public void run() { RemoteActions.media(cmd, seconds); } });
        }

        /** Niveau absolu 0..1 du curseur ; plus fort si au moins egal au niveau precedent (volume_key_for). */
        void setVolume(double level) {
            final boolean up = level >= currentVolume();
            synchronized (Companion.class) { volume = Math.max(0, Math.min(100, level * 100)); }
            final float l = (float) Math.max(0, Math.min(1, level));
            act(new Runnable() { public void run() { RemoteActions.setVolume(l, up); } });
        }

        double currentVolume() {
            float v = RemoteActions.currentVolume();
            if (v >= 0) return Math.min(1, v);
            synchronized (Companion.class) { return volume / 100.0; }
        }

        void volumeChanged(double v) {
            synchronized (Companion.class) { volume = Math.max(0, Math.min(100, v)); }
            event("_iMC", 1234L, Opack.map("_mcF", MEDIA_FLAGS));
        }

        void interest(Map<?, ?> m) {
            Map<?, ?> c = content(m);
            if (c.containsKey("_regEvents")) {
                List<?> events = (List<?>) c.get("_regEvents");
                for (Object e : events) interests.add(String.valueOf(e));
                if (interests.contains("_iMC")) event("_iMC", xid(m), Opack.map("_mcF", MEDIA_FLAGS));
                // valeur initiale des abonnements dont on connait le format
                for (Object e : events) {
                    if ("MediaControlStatus".equals(e)) event("MediaControlStatus", xid(m), Opack.map("MediaControlFlags", MEDIA_FLAGS));
                    else if ("NowPlayingInfo".equals(e)) event("NowPlayingInfo", xid(m), Opack.map());
                }
            } else if (c.containsKey("_deregEvents")) {
                for (Object e : (List<?>) c.get("_deregEvents")) interests.remove(String.valueOf(e));
            }
        }

        // ---------------------------------------------------------- saisie de texte (RTI)

        /**
         * Ouvre la session de texte. Sans champ actif sur la TV, reponse vide : pas de clavier tant que keyboard(true)
         * n'est pas appele (sinon iOS l'ouvre des la connexion). Champ deja actif : reponse avec le texte, comme une
         * Apple TV (iOS n'aura pas de _tiStarted pour ce focus).
         */
        void textStart(Map<?, ?> m) {
            if (!Long.valueOf(2).equals(m.get("_t"))) return;
            String field = fieldText;
            Object payload;
            synchronized (rtiLock) {
                rtiUuid = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
                rtiRegistered = true;
                if (field != null) {
                    rtiText = field;
                    rtiFocus = FOCUS_FOCUSED;
                    payload = rtiEncoded();
                } else {
                    rtiText = "";
                    rtiFocus = FOCUS_UNFOCUSED;
                    payload = Opack.map();
                }
            }
            respond(m, payload);
        }

        void textStop(Map<?, ?> m) {
            if (!Long.valueOf(2).equals(m.get("_t"))) return;
            boolean had;
            synchronized (rtiLock) {
                had = rtiUuid != null;
                if (had) { rtiUuid = null; rtiRegistered = false; }
            }
            if (had) respond(m, Opack.map());
            else Log.w(TAG, "_tiStop sans session de texte");
        }

        /** Frappe : insertion, effacement de N caracteres ou texte impose (correction) ; on renvoie le champ entier. */
        void textChange(Map<?, ?> m) {
            if (!Long.valueOf(1).equals(m.get("_t"))) return;
            Object assertText, insertion, deletion;
            try {
                Object archive = Opack.Plist.read((byte[]) content(m).get("_tiD"));
                assertText = Opack.Plist.archiveProperty(archive, "textOperations", "textToAssert");
                insertion = Opack.Plist.archiveProperty(archive, "textOperations", "keyboardOutput", "insertionText");
                deletion = Opack.Plist.archiveProperty(archive, "textOperations", "keyboardOutput", "deletionCount");
            } catch (RuntimeException e) {
                malformed("message de texte illisible");
                return;
            }
            final String text;
            synchronized (rtiLock) {
                String old = rtiText != null ? rtiText : "";
                String t = old;
                if (assertText != null) t = nsString(assertText);
                long n = deletion instanceof Number ? ((Number) deletion).longValue() : deletion instanceof Boolean && (Boolean) deletion ? 1 : 0;
                if (n != 0) t = t.substring(0, t.length() - (int) Math.max(0, Math.min(n, t.length())));
                if (insertion != null) t += nsString(insertion);
                if (t.length() > RTI_MAX_TEXT) t = t.substring(0, RTI_MAX_TEXT);
                rtiText = t;
                if (t.equals(old)) return;
                text = t;
                if (fieldText != null) fieldText = t;
            }
            act(new Runnable() { public void run() { RemoteActions.text(text); } });
        }

        void setFocus(boolean focused) {
            Object payload;
            synchronized (rtiLock) {
                if (!rtiRegistered || rtiUuid == null || !encrypted()) return;
                int f = focused ? FOCUS_FOCUSED : FOCUS_UNFOCUSED;
                if (f == rtiFocus) return;
                if (focused) { String field = fieldText; rtiText = field != null ? field : ""; }
                rtiFocus = f;
                payload = focused ? rtiEncoded() : Opack.map();
            }
            event(focused ? "_tiStarted" : "_tiStopped", 1234L, payload);
        }

        /** _tiD : archive NSKeyedArchiver minimale (uuid de session, texte avant le curseur), comme appletv.py. */
        Object rtiEncoded() {
            ArrayList<Object> objects = new ArrayList<>();
            objects.add("$null");
            objects.add(rtiUuid);
            objects.add(Opack.map("docSt", new Opack.Plist.Uid(3)));
            objects.add(Opack.map("contextBeforeInput", new Opack.Plist.Uid(4)));
            objects.add(rtiText);
            Map<Object, Object> archive = Opack.map(
                    "$top", Opack.map("sessionUUID", new Opack.Plist.Uid(1), "documentState", new Opack.Plist.Uid(2)),
                    "$objects", objects);
            return Opack.map("_tiD", Opack.Plist.write(archive));
        }
    }

    // ------------------------------------------------------------------ gestes du pave tactile (gestures.py, relay.py)

    /**
     * Pave 1000x1000, origine en haut a gauche. Tape (moins de 60 de course) = SELECT ; glissement de plus de 120
     * sur un axe nettement dominant (x1,3) = une fleche, plus une par 350 de course (10 au plus). Un doigt garde
     * 400 ms au-dela du seuil devient un appui maintenu : une fleche tout de suite, puis 250 ms plus tard une toutes
     * les 120 ms, jusqu'au relachement (15 s au plus).
     */
    static final class Touch {
        static final int TAP_MAX_TRAVEL = 60, SWIPE_THRESHOLD = 120, REPEAT_EVERY = 350, MAX_REPEAT = 10;
        static final double DOMINANT_RATIO = 1.3;
        static final long HOLD_ACTIVATE_MS = 400, REPEAT_DELAY_MS = 250, REPEAT_INTERVAL_MS = 120, MAX_HOLD_MS = 15_000;
        static final long SELECT_DEDUPE_MS = 400;
        static final int PRESS = 1, MOVE = 2, HOLD = 3, RELEASE = 4, CLICK = 5;

        final Conn conn;
        boolean tracking;
        int sx, sy, lx, ly;
        int holdDir;
        long holdSince;
        boolean holdActive, holdWasActive, suppressClick;
        final Object repeatLock = new Object();
        ScheduledFuture<?> repeat;
        final AtomicBoolean repeatPending = new AtomicBoolean();

        Touch(Conn c) { conn = c; }

        int lastX() { return tracking ? lx : 0; }
        int lastY() { return tracking ? ly : 0; }

        void onTouch(int phase, int x, int y) {
            if (phase == PRESS) {
                endHold();   // un relachement perdu : le nouvel appui termine l'ancien maintien
                resetHold();
                suppressClick = false;
                tracking = true;
                sx = lx = x;
                sy = ly = y;
            } else if (phase == MOVE || phase == HOLD) {
                if (tracking) { lx = x; ly = y; }
                updateHold();
            } else if (phase == RELEASE || phase == CLICK) {
                endHold();
                int[] r;
                if (phase == CLICK) { tracking = false; r = new int[]{RemoteActions.SELECT, 1}; }
                else if (!tracking) r = null;
                else { tracking = false; r = resolve(x - sx, y - sy); }
                if (holdWasActive) suppressClick = true;   // fin d'un maintien : pas de fleche ni de tape en plus
                else if (suppressClick && r != null && r[0] == RemoteActions.SELECT) { /* tape parasite apres un maintien */ }
                else if (r != null) emit(r[0], r[1]);
                resetHold();
            }
        }

        void emit(int dir, int count) {
            if (dir == RemoteActions.SELECT) { conn.tap(); return; }
            for (int i = 0; i < count; i++) conn.swipe(dir);
        }

        /** {direction, nombre} ou null (zone morte, diagonale). */
        static int[] resolve(int dx, int dy) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= TAP_MAX_TRAVEL) return new int[]{RemoteActions.SELECT, 1};
            int[] d = dominant(dx, dy);
            if (d == null) return null;
            int n = REPEAT_EVERY <= 0 ? 1 : Math.min(Math.max(1, d[1] / REPEAT_EVERY), MAX_REPEAT);
            return new int[]{d[0], n};
        }

        static int[] dominant(int dx, int dy) {
            int ax = Math.abs(dx), ay = Math.abs(dy);
            if (Math.max(ax, ay) < SWIPE_THRESHOLD) return null;
            if (ax >= ay) {
                if (ax < ay * DOMINANT_RATIO) return null;
                return new int[]{dx > 0 ? RemoteActions.RIGHT : RemoteActions.LEFT, ax};
            }
            if (ay < ax * DOMINANT_RATIO) return null;
            return new int[]{dy > 0 ? RemoteActions.DOWN : RemoteActions.UP, ay};
        }

        int currentDirection() {
            if (!tracking) return 0;
            int[] d = dominant(lx - sx, ly - sy);
            return d == null ? 0 : d[0];
        }

        void updateHold() {
            long t = now();
            int d = currentDirection();
            if (d != holdDir) {
                endHold();
                holdDir = d;
                holdSince = d != 0 ? t : 0;
            } else if (d != 0 && !holdActive && t - holdSince >= HOLD_ACTIVATE_MS) {
                holdActive = true;
                holdWasActive = true;
                conn.swipe(d);   // premier pas tout de suite, la repetition suit
                startRepeat(d);
            }
        }

        void endHold() {
            if (holdActive && holdDir != 0) stopRepeat();
            holdActive = false;
        }

        void resetHold() {
            holdDir = 0;
            holdSince = 0;
            holdActive = false;
            holdWasActive = false;
        }

        void startRepeat(final int dir) {
            synchronized (repeatLock) {
                stopRepeat();
                final long start = now();
                final int g = conn.gen;
                final ScheduledFuture<?>[] self = new ScheduledFuture<?>[1];
                Runnable tick = new Runnable() {
                    public void run() {
                        if (conn.closed || conn.gen != g || now() - start > MAX_HOLD_MS) {
                            synchronized (repeatLock) { if (repeat == self[0]) stopRepeat(); }
                            return;
                        }
                        // une action lente a la TV : on saute un pas plutot que d'empiler
                        if (!repeatPending.compareAndSet(false, true)) return;
                        post(new Runnable() {
                            public void run() {
                                try {
                                    if (!conn.closed && conn.gen == g && repeat == self[0]) RemoteActions.swipe(dir);
                                } finally {
                                    repeatPending.set(false);
                                }
                            }
                        });
                    }
                };
                try {
                    self[0] = timer.scheduleAtFixedRate(tick, REPEAT_DELAY_MS, REPEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    repeat = self[0];
                } catch (RejectedExecutionException e) {
                    Log.w(TAG, "repetition", e);
                }
            }
        }

        void stopRepeat() {
            synchronized (repeatLock) {
                if (repeat != null) repeat.cancel(false);
                repeat = null;
            }
        }
    }

    // ------------------------------------------------------------------ SRP-6a (srptools, variante HAP)

    /**
     * Cote serveur de SRP-6a, groupe RFC 5054 3072 bits, g = 5, SHA-512, utilisateur "Pair-Setup", sel de 128 bits.
     * Calcule exactement comme srptools 1.0.1 (pyatv) : les entiers deviennent des octets sans zeros de tete, sauf
     * dans k et u (completes a la taille de N). K = H(S), M1 = H(H(N) xor H(g), H(I), s, A, B, K), M2 = H(A, M1, K).
     */
    static final class Srp {
        static final BigInteger N = new BigInteger(
                "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A4"
                + "31B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4"
                + "B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED52907"
                + "7096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BC"
                + "BF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AE"
                + "A71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A645"
                + "21F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
        static final BigInteger G = BigInteger.valueOf(5);
        static final String USER = "Pair-Setup";
        static final BigInteger K_MULT = hashInt(bytes(N), pad(G));

        final byte[] salt;
        final BigInteger v, b, bPub;
        byte[] key, serverProof;

        Srp(String pin) { this(pin, new BigInteger(128, random), new BigInteger(1024, random)); }

        /** Sel et secret b fixes : pour les tests. */
        Srp(String pin, BigInteger saltInt, BigInteger b) {
            salt = bytes(saltInt);
            BigInteger x = hashInt(salt, sha("SHA-512", (USER + ":" + pin).getBytes(StandardCharsets.UTF_8)));
            v = G.modPow(x, N);
            this.b = b;
            bPub = K_MULT.multiply(v).add(G.modPow(b, N)).mod(N);
        }

        byte[] publicKey() { return bytes(bPub); }

        /** M3 : A et la preuve du client ; vrai si le code est le bon (key et serverProof sont alors prets). */
        boolean verify(byte[] aBytes, byte[] clientProof) {
            if (aBytes == null || clientProof == null) throw new IllegalArgumentException("M3 incomplet");
            BigInteger a = new BigInteger(1, aBytes);
            if (a.mod(N).signum() == 0) throw new IllegalArgumentException("A invalide");
            BigInteger u = hashInt(pad(a), pad(bPub));
            BigInteger s = a.multiply(v.modPow(u, N)).modPow(b, N);
            key = sha("SHA-512", bytes(s));
            byte[] hx = bytes(hashInt(bytes(N)).xor(hashInt(bytes(G))));
            byte[] m1 = sha("SHA-512", hx, bytes(hashInt(USER.getBytes(StandardCharsets.UTF_8))), salt, bytes(a), bytes(bPub), key);
            serverProof = sha("SHA-512", bytes(a), m1, key);
            return MessageDigest.isEqual(m1, clientProof);
        }

        static BigInteger hashInt(byte[]... parts) { return new BigInteger(1, sha("SHA-512", parts)); }

        /** Octets big-endian sans zero de tete (int_to_bytes de srptools ; 0 donne un octet nul). */
        static byte[] bytes(BigInteger v) {
            byte[] r = v.toByteArray();
            if (r.length > 1 && r[0] == 0) r = Arrays.copyOfRange(r, 1, r.length);
            return r;
        }

        static byte[] pad(BigInteger v) {
            byte[] r = bytes(v);
            int n = bytes(N).length;
            if (r.length >= n) return r;
            byte[] p = new byte[n];
            System.arraycopy(r, 0, p, n - r.length, r.length);
            return p;
        }
    }

    // ------------------------------------------------------------------ limites d'appairage (guardrails.py)

    /** Fenetre glissante de 60 s, par adresse (256 suivies au plus) et au total. */
    static final class Limiter {
        final int perSource, global;
        final ArrayDeque<Long> all = new ArrayDeque<>();
        final LinkedHashMap<String, ArrayDeque<Long>> bySource = new LinkedHashMap<>();

        Limiter(int perSource, int global) { this.perSource = perSource; this.global = global; }

        /** Demande d'appairage (M1) : null si admise (et comptee), sinon {erreur HAP, attente en s}. */
        synchronized int[] admit(String source) {
            long t = now();
            prune(t);
            ArrayDeque<Long> s = bySource.get(source);
            if (s != null && s.size() >= perSource) return new int[]{Opack.Tlv.ERR_MAX_TRIES, retryAfter(t, s.peekFirst())};
            if (all.size() >= global) return new int[]{Opack.Tlv.ERR_BUSY, retryAfter(t, all.peekFirst())};
            add(source, t);
            return null;
        }

        /** Echecs : secondes d'attente avant un nouvel essai, 0 si permis. */
        synchronized int check(String source) {
            long t = now();
            prune(t);
            ArrayDeque<Long> s = bySource.get(source);
            long oldest = Long.MAX_VALUE;
            if (all.size() >= global) oldest = all.peekFirst();
            if (s != null && s.size() >= perSource) oldest = Math.min(oldest, s.peekFirst());
            return oldest == Long.MAX_VALUE ? 0 : retryAfter(t, oldest);
        }

        synchronized void record(String source) {
            long t = now();
            prune(t);
            ArrayDeque<Long> s = bySource.get(source);
            if (all.size() >= global || (s != null && s.size() >= perSource)) return;
            add(source, t);
        }

        void add(String source, long t) {
            ArrayDeque<Long> s = bySource.remove(source);   // remis en fin : le moins recent sort en premier
            if (s == null) {
                s = new ArrayDeque<>();
                if (bySource.size() >= 256) { Iterator<String> it = bySource.keySet().iterator(); it.next(); it.remove(); }
            }
            bySource.put(source, s);
            s.addLast(t);
            all.addLast(t);
        }

        static int retryAfter(long t, long oldest) { return (int) Math.max(1, (LIMIT_WINDOW_MS - (t - oldest) + 999) / 1000); }

        void prune(long t) {
            long cutoff = t - LIMIT_WINDOW_MS;
            while (!all.isEmpty() && all.peekFirst() <= cutoff) all.removeFirst();
            Iterator<Map.Entry<String, ArrayDeque<Long>>> it = bySource.entrySet().iterator();
            while (it.hasNext()) {
                ArrayDeque<Long> s = it.next().getValue();
                while (!s.isEmpty() && s.peekFirst() <= cutoff) s.removeFirst();
                if (s.isEmpty()) it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ outils

    /** Code d'appairage : 4 chiffres au hasard, sans les codes faibles (pairing_window.py). */
    static String newPin() {
        while (true) {
            String p = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
            if (strongPin(p)) return p;
        }
    }

    static boolean strongPin(String p) {
        if (p.length() != 4) return false;
        for (String w : new String[]{"0000", "1234", "4321", "1212", "1122", "2580", "6969"}) if (w.equals(p)) return false;
        boolean same = true, up = true, down = true;
        for (int i = 1; i < 4; i++) {
            int a = p.charAt(i - 1) - '0', b = p.charAt(i) - '0';
            if (a != b) same = false;
            if (a + 1 != b) up = false;
            if (a - 1 != b) down = false;
        }
        return !same && !up && !down;
    }

    static void post(Runnable r) {
        try { actions.execute(r); }
        catch (RejectedExecutionException e) { Log.w(TAG, "trop d'actions en attente, action perdue"); }
    }

    static ThreadFactory daemon(final String n) {
        return new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, n);
                t.setDaemon(true);
                return t;
            }
        };
    }

    static long now() { return System.nanoTime() / 1_000_000L; }

    // Trace des messages chiffres (logcat -s ZenCompanion, niveau D) : de quoi comparer avec une vraie Apple TV.
    // Les octets (texte RTI, archives) n'apparaissent que par leur taille, les identifiants iCloud sont masques.
    static final boolean TRACE = true;

    static String trace(Object o) {
        StringBuilder b = new StringBuilder();
        trace(b, o, 0);
        return b.length() > 600 ? b.substring(0, 600) + "..." : b.toString();
    }

    static void trace(StringBuilder b, Object o, int depth) {
        if (o instanceof Map) {
            if (depth > 3) { b.append("{...}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(", ");
                first = false;
                b.append(e.getKey()).append('=');
                String k = String.valueOf(e.getKey());
                if (k.contains("DSID") || k.equals("_pubID") || k.equals("_idsID")) b.append("<masque>");   // identifiants du compte
                else trace(b, e.getValue(), depth + 1);
            }
            b.append('}');
        } else if (o instanceof List) {
            if (depth > 3) { b.append("[...]"); return; }
            b.append('[');
            boolean first = true;
            for (Object x : (List<?>) o) { if (!first) b.append(", "); first = false; trace(b, x, depth + 1); }
            b.append(']');
        } else if (o instanceof byte[]) {
            b.append('<').append(((byte[]) o).length).append(" octets>");
        } else if (o instanceof String) {
            String t = (String) o;
            b.append('"').append(t.length() > 40 ? t.substring(0, 40) + "..." : t).append('"');
        } else b.append(o);
    }

    static Map<?, ?> content(Map<?, ?> m) {
        Object c = m.get("_c");
        if (!(c instanceof Map)) throw new IllegalArgumentException("message sans _c");
        return (Map<?, ?>) c;
    }

    /** Nombre OPACK en entier (int() de Python : un reel est tronque). */
    static long num(Object o) {
        if (o instanceof Number) return o instanceof Double || o instanceof Float ? (long) ((Number) o).doubleValue() : ((Number) o).longValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        throw new IllegalArgumentException("nombre attendu");
    }

    /** NSString d'une archive : texte direct, ou NSMutableString {"NS.string": ...}. */
    static String nsString(Object o) {
        if (o instanceof Map && ((Map<?, ?>) o).get("NS.string") instanceof String) return (String) ((Map<?, ?>) o).get("NS.string");
        return String.valueOf(o);
    }

    static byte[] hkdf(byte[] ikm, String salt, String info) {
        return Crypto.hkdfSha512(ikm, salt.getBytes(StandardCharsets.UTF_8), info.getBytes(StandardCharsets.UTF_8), 32);
    }

    /** Nonce ChaCha20 de session : compteur sur 12 octets, petit-boutiste. */
    static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[i] = (byte) (counter >>> (8 * i));
        return n;
    }

    /** Nonce d'appairage ("PS-Msg05"...) : 4 zeros puis les 8 caracteres. */
    static byte[] fixedNonce(String s) {
        byte[] n = new byte[12];
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, n, 12 - b.length, b.length);
        return n;
    }

    static String utf8Strict(byte[] b) {
        if (b == null) throw new IllegalArgumentException("identifiant absent");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("UTF-8 invalide");
        }
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    static byte[] sha(String alg, byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance(alg);
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
