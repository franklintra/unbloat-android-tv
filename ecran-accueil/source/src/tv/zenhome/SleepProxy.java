package tv.zenhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client Bonjour Sleep Proxy : la TV reste visible des iPhones pendant la veille rapide (STR).
 *
 * En STR, la puce Wi-Fi ne reveille la TV que sur un paquet unicast ; le mDNS multicast ne la reveille pas et
 * mdnsd est gele. Un iPhone qui ouvre la telecommande ne trouve donc plus "Chambre Franklin" et n'envoie jamais
 * le SYN qui reveillerait la TV. Comme un Mac qui s'endort, on confie nos enregistrements mDNS a un proxy de
 * veille du reseau (_sleep-proxy._udp, ici l'Apple TV "Salle de loisirs") : il repond a notre place, l'iPhone
 * resout l'adresse de la TV et s'y connecte directement, et ce SYN unicast reveille la puce (Companion accepte,
 * puis un bouton rallume l'ecran).
 *
 * Fonctionnement, repris de mDNSResponder (mDNSCore SendSPSRegistration) et verifie contre l'Apple TV :
 * - Releve : quand la TV est allumee, on demande a notre propre mdnsd (requete unicast "legacy" sur notre
 *   adresse, port 5353) les PTR, SRV et TXT de _companion-link._tcp, _airplay._tcp et _raop._tcp, et l'adresse A
 *   de l'hote cible (Android.local). On garde les octets exacts : un TXT ou un SRV different de celui de mdnsd
 *   serait un conflit mDNS et la TV changerait de nom au reveil.
 * - Ecran eteint : DNS UPDATE (RFC 2136, opcode 5, sans zone) en UDP unicast vers le port SRV du proxy, avec ces
 *   enregistrements dans la section mise a jour (bit "cache flush" sur SRV, TXT et A), et un OPT EDNS0 portant
 *   l'option Lease (2, LEASE_S secondes) et l'option Owner (4 : version 0, numero de sequence, adresse MAC).
 * - Renouvellement : alarme RTC_WAKEUP toutes les RENEW_MS tant que l'ecran reste eteint, meme seq.
 * - Ecran allume : une requete mDNS portant l'option Owner avec un nouveau numero de sequence, envoyee au
 *   proxy (unicast) et en multicast ; le proxy efface alors tout ce qu'il tenait pour nous (verifie : une mise
 *   a jour vide ne suffit pas, l'Apple TV garde les enregistrements).
 * Pas de PTR inverse (in-addr.arpa) : sans lui le proxy ne prend pas notre adresse IP (pas de proxy ARP ni de
 * paquet magique). Le trafic vers la TV reste pour la TV : adb, Spotify Connect et Cast la reveillent comme avant.
 */
final class SleepProxy {

    static final String TAG = "ZenSleepProxy";
    static final String[] TYPES = { "_companion-link._tcp", "_airplay._tcp", "_raop._tcp" };
    static final int LEASE_S = 7200, TTL_HOST = 120, TTL_SHARED = 4500;
    static final long RENEW_MS = 90 * 60_000L, SNAPSHOT_DELAY_MS = 15_000, LOCK_MS = 10_000;
    static final int T_A = 1, T_PTR = 12, T_TXT = 16, T_SRV = 33, T_OPT = 41, CLASS_IN = 1, CACHE_FLUSH = 0x8000;
    static final String RENEW = "tv.zenhome.SLEEP_PROXY_RENEW";

    static final ExecutorService bg = Executors.newSingleThreadExecutor(Companion.daemon("zen-sleep-proxy"));
    static final SecureRandom random = new SecureRandom();
    static Context ctx;
    static volatile List<byte[]> records;        // releve : enregistrements encodes, prets pour la mise a jour
    static volatile byte[] hostName;             // nom de l'hote cible des SRV (format DNS)
    static volatile InetSocketAddress proxy;     // proxy choisi (adresse, port de son SRV)
    static volatile boolean registered;
    static volatile long snapshotAt;

    private SleepProxy() { }

    // ------------------------------------------------------------------ cycle de vie (Keys)

    /**
     * Keys.onServiceConnected : releve des enregistrements une fois les annonces faites. Service redemarre ecran
     * eteint (mise a jour de l'appli pendant la veille) : on s'enregistre aupres du proxy sans attendre SCREEN_OFF.
     */
    static void start(Context c) {
        ctx = c.getApplicationContext();
        bg.execute(new Runnable() {
            public void run() {
                SystemClock.sleep(SNAPSHOT_DELAY_MS);
                if (awake()) { snapshot(); return; }
                PowerManager.WakeLock l = lock();
                try { register(true); } finally { if (l.isHeld()) l.release(); }
            }
        });
    }

    static void stop() { cancelRenew(); }

    /** Keys, SCREEN_ON : le proxy rend la main, puis nouveau releve (les annonces viennent d'etre refaites). */
    static void onScreenOn(Context c) {
        ctx = c.getApplicationContext();
        cancelRenew();
        bg.execute(new Runnable() { public void run() { deregister(); } });
        snapshotLater();
    }

    /** Keys, SCREEN_OFF : la STR suit dans ~4 s ; on confie nos enregistrements au proxy tout de suite. */
    static void onScreenOff(Context c) {
        ctx = c.getApplicationContext();
        final PowerManager.WakeLock l = lock();
        bg.execute(new Runnable() {
            public void run() {
                try {
                    if (!awake()) register(true);
                } finally { if (l.isHeld()) l.release(); }
            }
        });
    }

    static void snapshotLater() {
        bg.execute(new Runnable() {
            public void run() {
                SystemClock.sleep(SNAPSHOT_DELAY_MS);
                if (awake()) snapshot();
            }
        });
    }

    static boolean awake() {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    static PowerManager.WakeLock lock() {
        PowerManager.WakeLock l = ((PowerManager) ctx.getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zenhome:sleep-proxy");
        l.setReferenceCounted(false);
        l.acquire(LOCK_MS);
        return l;
    }

    // ------------------------------------------------------------------ releve de nos enregistrements

    /** Demande a notre mdnsd ce qu'il annonce pour nous ; fil bg. */
    static void snapshot() {
        InetAddress self = ownAddress();
        if (self == null) { Log.w(TAG, "releve : pas d'adresse IPv4"); return; }
        ArrayList<byte[]> out = new ArrayList<>();
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        byte[] host = null;
        int services = 0;
        try {
            for (String type : TYPES) {
                byte[] typeName = name(type + ".local");
                for (Rec ptr : query(self, typeName, T_PTR, 600, false)) {
                    if (ptr.type != T_PTR || !sameName(ptr.name, typeName)) continue;
                    byte[] inst = ptr.rdata;
                    Rec srv = first(query(self, inst, T_SRV, 600, false), inst, T_SRV);
                    Rec txt = first(query(self, inst, T_TXT, 600, false), inst, T_TXT);
                    if (srv == null || txt == null) { Log.w(TAG, "releve : SRV ou TXT absent pour " + text(inst)); continue; }
                    out.add(rr(name("_services._dns-sd._udp.local"), T_PTR, CLASS_IN, TTL_SHARED, typeName));
                    out.add(rr(typeName, T_PTR, CLASS_IN, TTL_SHARED, inst));
                    out.add(rr(inst, T_SRV, CLASS_IN | CACHE_FLUSH, TTL_HOST, srv.rdata));
                    out.add(rr(inst, T_TXT, CLASS_IN | CACHE_FLUSH, TTL_SHARED, txt.rdata));
                    byte[] target = Arrays.copyOfRange(srv.rdata, 6, srv.rdata.length);
                    if (hosts.add(text(target))) {
                        for (Rec a : query(self, target, T_A, 600, false))
                            if (a.type == T_A && a.rdata.length == 4 && sameName(a.name, target))
                                out.add(rr(target, T_A, CLASS_IN | CACHE_FLUSH, TTL_HOST, a.rdata));
                        if (host == null) host = target;
                    }
                    services++;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "releve", e);
            return;
        }
        if (services == 0) { Log.w(TAG, "releve : aucun service trouve aupres de mdnsd"); return; }
        records = out;
        hostName = host;
        snapshotAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "releve : " + services + " service(s), " + out.size() + " enregistrements, hote " + text(host));
    }

    // ------------------------------------------------------------------ proxy

    /** Proxy de veille du reseau : le meilleur (premiers nombres du nom les plus petits) ; fil bg. */
    static InetSocketAddress findProxy() throws IOException {
        byte[] type = name("_sleep-proxy._udp.local");
        Rec best = null;
        long bestMetric = Long.MAX_VALUE;
        Pattern p = Pattern.compile("^(\\d+)-(\\d+)-(\\d+)-(\\d+)");
        for (Rec r : query(InetAddress.getByName("224.0.0.251"), type, T_PTR, 1200, true)) {
            if (r.type != T_PTR || !sameName(r.name, type)) continue;
            Matcher m = p.matcher(firstLabel(r.rdata));
            long metric = Long.MAX_VALUE - 1;
            if (m.find()) metric = (Long.parseLong(m.group(1)) << 24) | (Long.parseLong(m.group(2)) << 16)
                    | (Long.parseLong(m.group(3)) << 8) | Long.parseLong(m.group(4));
            if (metric < bestMetric) { bestMetric = metric; best = r; }
        }
        if (best == null) return null;
        Rec srv = first(query(best.from, best.rdata, T_SRV, 800, false), best.rdata, T_SRV);
        if (srv == null) return null;
        int port = (srv.rdata[4] & 0xff) << 8 | (srv.rdata[5] & 0xff);
        Log.i(TAG, "proxy : " + firstLabel(best.rdata) + " " + best.from.getHostAddress() + ":" + port);
        return new InetSocketAddress(best.from, port);
    }

    /** Envoie (ou renouvelle) l'enregistrement ; fil bg. newSeq : nouvelle mise en veille. */
    static void register(boolean newSeq) {
        if (records == null) snapshot();   // le releve aurait du etre fait ecran allume ; dernier essai
        List<byte[]> recs = records;
        if (recs == null) { Log.w(TAG, "veille : rien a confier au proxy"); return; }
        int seq = newSeq ? nextSeq() : seq();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (proxy == null || attempt > 0) proxy = findProxy();
                if (proxy == null) { Log.w(TAG, "veille : aucun proxy _sleep-proxy._udp sur le reseau"); return; }
                int lease = update(proxy, recs, seq);
                if (lease >= 0) {
                    registered = true;
                    Log.i(TAG, "veille : " + recs.size() + " enregistrements confies au proxy, bail " + lease + " s, seq " + seq);
                    scheduleRenew();
                    return;
                }
            } catch (IOException e) {
                Log.w(TAG, "veille : envoi au proxy", e);
            }
            SystemClock.sleep(300);
        }
        Log.w(TAG, "veille : le proxy n'a pas accepte l'enregistrement");
    }

    /** DNS UPDATE + OPT (Lease, Owner) ; rend le bail accorde, ou -1 (refus ou pas de reponse). */
    static int update(InetSocketAddress to, List<byte[]> recs, int seq) throws IOException {
        int id = random.nextInt(0x10000);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        u16(b, id); u16(b, 0x2800); u16(b, 0); u16(b, 0); u16(b, recs.size()); u16(b, 1);
        for (byte[] r : recs) b.write(r, 0, r.length);
        byte[] opt = optRecord(LEASE_S, seq);
        b.write(opt, 0, opt.length);
        byte[] msg = b.toByteArray();
        DatagramSocket s = new DatagramSocket();
        try {
            s.setSoTimeout(1000);
            s.send(new DatagramPacket(msg, msg.length, to));
            byte[] buf = new byte[1500];
            long end = SystemClock.elapsedRealtime() + 1000;
            while (SystemClock.elapsedRealtime() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { return -1; }
                if (p.getLength() < 12 || u16(buf, 0) != id) continue;
                int rcode = buf[3] & 15;
                if (rcode != 0) { Log.w(TAG, "proxy : refus, code " + rcode); return -1; }
                int lease = grantedLease(buf, p.getLength());
                return lease > 0 ? lease : LEASE_S;
            }
            return -1;
        } finally { s.close(); }
    }

    /** Reveil : le proxy efface nos enregistrements a la vue de l'option Owner avec un nouveau seq ; fil bg. */
    static void deregister() {
        if (!registered && proxy == null) return;
        int seq = nextSeq();
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            u16(b, 0); u16(b, 0); u16(b, 1); u16(b, 0); u16(b, 0); u16(b, 1);
            byte[] q = hostName != null ? hostName : name("Android.local");
            b.write(q, 0, q.length); u16(b, T_A); u16(b, CLASS_IN);
            byte[] opt = optRecord(0, seq);
            b.write(opt, 0, opt.length);
            byte[] msg = b.toByteArray();
            DatagramSocket s = new DatagramSocket();
            try {
                if (proxy != null) s.send(new DatagramPacket(msg, msg.length, new InetSocketAddress(proxy.getAddress(), 5353)));
                s.send(new DatagramPacket(msg, msg.length, new InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353)));
            } finally { s.close(); }
            registered = false;
            Log.i(TAG, "reveil : le proxy rend nos enregistrements (seq " + seq + ")");
        } catch (IOException e) {
            Log.w(TAG, "reveil : message au proxy", e);
        }
    }

    // ------------------------------------------------------------------ renouvellement (ecran eteint)

    /** Alarme de renouvellement, recue meme en STR (RTC_WAKEUP). */
    public static class Renew extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            ctx = c.getApplicationContext();
            if (awake()) return;
            final PowerManager.WakeLock l = lock();
            final PendingResult r = goAsync();
            bg.execute(new Runnable() {
                public void run() {
                    try { if (!awake()) { Log.i(TAG, "renouvellement du bail"); register(false); } }
                    finally { if (l.isHeld()) l.release(); r.finish(); }
                }
            });
        }
    }

    static PendingIntent renewIntent() {
        return PendingIntent.getBroadcast(ctx, 0, new Intent(RENEW).setClass(ctx, Renew.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void scheduleRenew() {
        try {
            ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + RENEW_MS, renewIntent());
        } catch (Exception e) { Log.w(TAG, "alarme de renouvellement", e); }
    }

    static void cancelRenew() {
        if (ctx == null) return;
        try { ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(renewIntent()); }
        catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ identite (option Owner)

    static SharedPreferences prefs() { return ctx.getSharedPreferences("sleep-proxy", Context.MODE_PRIVATE); }
    static synchronized int seq() { return prefs().getInt("seq", 0) & 0xff; }
    static synchronized int nextSeq() {
        int s = (prefs().getInt("seq", 0) + 1) & 0xff;
        prefs().edit().putInt("seq", s).commit();
        return s;
    }

    /** OPT EDNS0 : Lease (si lease > 0) puis Owner (version 0, seq, MAC du Wi-Fi). */
    static byte[] optRecord(int lease, int seq) {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        if (lease > 0) { u16(d, 2); u16(d, 4); u32(d, lease); }
        byte[] mac = mac();
        u16(d, 4); u16(d, 2 + mac.length); d.write(0); d.write(seq);
        d.write(mac, 0, mac.length);
        byte[] data = d.toByteArray();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0); u16(b, T_OPT); u16(b, 1440); u32(b, 0); u16(b, data.length);
        b.write(data, 0, data.length);
        return b.toByteArray();
    }

    /** MAC du Wi-Fi (le proxy s'en sert pour nous reconnaitre) ; a defaut une adresse locale fixe, gardee. */
    static byte[] mac() {
        try {
            NetworkInterface ni = NetworkInterface.getByName("wlan0");
            byte[] m = ni != null ? ni.getHardwareAddress() : null;
            if (m != null && m.length == 6 && (m[0] | m[1] | m[2] | m[3] | m[4] | m[5]) != 0) return m;
        } catch (Exception ignored) { }
        String s = prefs().getString("mac", null);
        if (s == null) {
            byte[] m = new byte[6];
            random.nextBytes(m);
            m[0] = (byte) ((m[0] | 0x02) & 0xfe);   // adresse administree localement, unicast
            s = Companion.hex(m);
            prefs().edit().putString("mac", s).commit();
        }
        return Companion.unhex(s);
    }

    static InetAddress ownAddress() {
        try {
            for (String n : new String[] { "wlan0", "eth0" }) {
                NetworkInterface ni = NetworkInterface.getByName(n);
                if (ni == null || !ni.isUp()) continue;
                for (Enumeration<InetAddress> e = ni.getInetAddresses(); e.hasMoreElements(); ) {
                    InetAddress a = e.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ mDNS minimal

    static final class Rec { byte[] name; int type, cls; byte[] rdata; InetAddress from; }

    /**
     * Requete mDNS "legacy unicast" (port source quelconque) : le repondeur repond en unicast a notre port.
     * many : multicast, on recueille les reponses de tous jusqu'au delai ; sinon la premiere suffit.
     */
    static List<Rec> query(InetAddress dest, byte[] qname, int qtype, int timeoutMs, boolean many) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int id = random.nextInt(0x10000);
        u16(b, id); u16(b, 0); u16(b, 1); u16(b, 0); u16(b, 0); u16(b, 0);
        b.write(qname, 0, qname.length); u16(b, qtype); u16(b, CLASS_IN);
        byte[] msg = b.toByteArray();
        ArrayList<Rec> out = new ArrayList<>();
        DatagramSocket s = new DatagramSocket();
        try {
            s.send(new DatagramPacket(msg, msg.length, new InetSocketAddress(dest, 5353)));
            byte[] buf = new byte[9000];
            long end = SystemClock.elapsedRealtime() + timeoutMs;
            while (true) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) break;
                s.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { break; }
                if (p.getLength() < 12 || u16(buf, 0) != id || (buf[2] & 0x80) == 0) continue;
                try { out.addAll(parse(buf, p.getLength(), p.getAddress())); }
                catch (RuntimeException e) { Log.d(TAG, "reponse mDNS illisible", e); }
                if (!many) break;
            }
        } finally { s.close(); }
        return out;
    }

    static List<Rec> parse(byte[] b, int len, InetAddress from) {
        int qd = u16(b, 4), n = u16(b, 6) + u16(b, 8) + u16(b, 10);
        int[] pos = { 12 };
        for (int i = 0; i < qd; i++) { readName(b, len, pos); pos[0] += 4; }
        ArrayList<Rec> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Rec r = new Rec();
            r.from = from;
            r.name = readName(b, len, pos);
            int p = pos[0];
            if (p + 10 > len) throw new IllegalArgumentException("tronque");
            r.type = u16(b, p); r.cls = u16(b, p + 2);
            int rdlen = u16(b, p + 8);
            p += 10;
            if (p + rdlen > len) throw new IllegalArgumentException("tronque");
            if (r.type == T_PTR) {
                r.rdata = readName(b, len, new int[] { p });
            } else if (r.type == T_SRV && rdlen > 6) {
                byte[] t = readName(b, len, new int[] { p + 6 });
                r.rdata = new byte[6 + t.length];
                System.arraycopy(b, p, r.rdata, 0, 6);
                System.arraycopy(t, 0, r.rdata, 6, t.length);
            } else {
                r.rdata = Arrays.copyOfRange(b, p, p + rdlen);
            }
            pos[0] = p + rdlen;
            out.add(r);
        }
        return out;
    }

    /** Nom DNS a partir de pos (pointeurs de compression suivis), rendu sans compression. */
    static byte[] readName(byte[] b, int len, int[] pos) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int p = pos[0], jumps = 0;
        boolean jumped = false;
        while (true) {
            if (p >= len) throw new IllegalArgumentException("nom tronque");
            int l = b[p] & 0xff;
            if ((l & 0xc0) == 0xc0) {
                if (p + 1 >= len || ++jumps > 64) throw new IllegalArgumentException("pointeur");
                if (!jumped) pos[0] = p + 2;
                jumped = true;
                p = (l & 0x3f) << 8 | (b[p + 1] & 0xff);
                continue;
            }
            if (l > 63 || p + 1 + l > len) throw new IllegalArgumentException("etiquette");
            o.write(b, p, 1 + l);
            p += 1 + l;
            if (o.size() > 255) throw new IllegalArgumentException("nom trop long");
            if (l == 0) break;
        }
        if (!jumped) pos[0] = p;
        return o.toByteArray();
    }

    static byte[] name(String dotted) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (String l : dotted.split("\\.")) {
            byte[] x = l.getBytes(StandardCharsets.UTF_8);
            o.write(x.length);
            o.write(x, 0, x.length);
        }
        o.write(0);
        return o.toByteArray();
    }

    static boolean sameName(byte[] a, byte[] b) {
        return new String(a, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT)
                .equals(new String(b, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT));
    }

    static String firstLabel(byte[] n) { return n.length > 1 ? new String(n, 1, n[0] & 0xff, StandardCharsets.UTF_8) : ""; }

    static String text(byte[] n) {
        if (n == null) return "?";
        StringBuilder s = new StringBuilder();
        for (int p = 0; p < n.length && n[p] != 0; p += 1 + (n[p] & 0xff))
            s.append(new String(n, p + 1, n[p] & 0xff, StandardCharsets.UTF_8)).append('.');
        return s.toString();
    }

    static Rec first(List<Rec> l, byte[] name, int type) {
        for (Rec r : l) if (r.type == type && sameName(r.name, name)) return r;
        return null;
    }

    static byte[] rr(byte[] name, int type, int cls, int ttl, byte[] rdata) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(name, 0, name.length);
        u16(b, type); u16(b, cls); u32(b, ttl); u16(b, rdata.length);
        b.write(rdata, 0, rdata.length);
        return b.toByteArray();
    }

    /** Bail accorde, lu dans l'option Lease de l'OPT de la reponse ; 0 si absent. */
    static int grantedLease(byte[] b, int len) {
        try {
            for (Rec r : parse(b, len, null)) {
                if (r.type != T_OPT) continue;
                for (int p = 0; p + 4 <= r.rdata.length; ) {
                    int code = u16(r.rdata, p), l = u16(r.rdata, p + 2);
                    if (code == 2 && l >= 4) return (int) ((r.rdata[p + 4] & 0xffL) << 24 | (r.rdata[p + 5] & 0xff) << 16 | (r.rdata[p + 6] & 0xff) << 8 | (r.rdata[p + 7] & 0xff));
                    p += 4 + l;
                }
            }
        } catch (RuntimeException ignored) { }
        return 0;
    }

    static int u16(byte[] b, int p) { return (b[p] & 0xff) << 8 | (b[p + 1] & 0xff); }
    static void u16(ByteArrayOutputStream o, int v) { o.write(v >> 8 & 0xff); o.write(v & 0xff); }
    static void u32(ByteArrayOutputStream o, long v) { u16(o, (int) (v >>> 16 & 0xffff)); u16(o, (int) (v & 0xffff)); }
}
