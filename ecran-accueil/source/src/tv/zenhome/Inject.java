package tv.zenhome;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Injecteur de touches : une appli normale ne peut pas injecter de KeyEvent (INJECT_EVENTS est reserve aux
 * applis signees plateforme), l'utilisateur shell (uid 2000) le peut. On lance donc un petit demon avec
 * app_process sous l'uid shell, comme le serveur de scrcpy, et Zen Home lui parle par une socket locale.
 *
 * Demon (uid shell), lance par AdbLocal au demarrage de Zen Home :
 *   CLASSPATH=<base.apk de Zen Home> nohup setsid app_process /system/bin --nice-name=zenhome.inject
 *       tv.zenhome.Inject <uid de Zen Home> <jeton> </dev/null >/dev/null 2>&1 & sleep 1; echo lance
 * nohup + setsid + entrees/sorties vers /dev/null : le demon survit a la fermeture de la session adb. Sans
 * shell_v2, adbd ouvre un pseudo-terminal et envoie SIGHUP a sa fermeture : nohup passe en premier (SIGHUP
 * ignore des avant setsid) et le shell attend 1 s avant de rendre la main (sinon le demon mourait avant de
 * demarrer, vu sur la TV avec la commande lancee par AdbLocal).
 * --nice-name renomme argv[0] (pas comm, qui reste "main") : "pidof zenhome.inject" trouve le demon sans
 * attraper le sh qui le lance, contrairement a "pkill -f".
 *
 * Socket : TCP 127.0.0.1:PORT, pas une LocalServerSocket. La politique SELinux interdit a une appli
 * (untrusted_app) de se connecter a une socket unix du domaine shell (neverallow appdomain -> shell
 * connectto), alors que le TCP local passe. Faute de getPeerCredentials sur TCP, le demon lit l'uid du
 * client dans /proc/net/tcp6 et /proc/net/tcp (lisibles par shell) et ferme toute connexion d'un autre uid.
 *
 * Jeton : n'importe quelle appli peut prendre le port 47813 avant le demon (apres un redemarrage, par exemple)
 * et recevoir alors les touches et le texte tape sur l'iPhone. Zen Home tire donc un jeton au hasard, garde
 * dans ses prefs privees ("inject", "token"), et le passe au demon en argument (/proc/<pid>/cmdline est cache
 * aux autres applis). Le jeton ne circule jamais : chaque cote prouve qu'il le connait par un HMAC-SHA256 sur
 * un nombre tire par l'autre, en deux lignes au debut de chaque connexion :
 *   client : "hello <nc>"            demon : "ok <nd> <HMAC(jeton, "demon " + nc)>"
 *   client : "auth <HMAC(jeton, "client " + nd)>"    demon : "ok", ou ferme la connexion
 * Un faux demon ne recoit donc rien d'utile, et un faux client ne peut rien injecter.
 *
 * Protocole ensuite ligne par ligne (UTF-8, "\n"), une reponse par commande, "ok" ou "err <raison>" :
 *   version        -> "ok <VERSION>"
 *   key <code>     appui complet (bas + haut)
 *   down <code>    touche enfoncee, repetee comme une vraie telecommande tant que "up" n'arrive pas
 *   up <code>      touche relachee
 *   long <code>    appui long : bas, repetitions (la premiere porte FLAG_LONG_PRESS), haut apres LONG_MS
 *   text <texte>   texte tape touche par touche ; "\\" et "\n" echappes en "\\\\" et "\\n"
 *   quit           le demon s'arrete
 * Les touches partent dans une file (un seul fil) : "ok" revient tout de suite, l'ordre est garanti.
 *
 * Changer le code du demon = augmenter VERSION : ensure() tue alors l'ancien demon et lance le nouveau.
 */
final class Inject {

    static final String TAG = "ZenInject", NAME = "zenhome.inject";
    static final int VERSION = 2, PORT = 47813;
    static final long REPEAT_START_MS = 400, REPEAT_EVERY_MS = 50, LONG_MS = 700, HOLD_MAX_MS = 30_000;
    static final Charset UTF8 = Charset.forName("UTF-8");

    // ---------------------------------------------------------------- demon (uid shell, app_process)

    static int allowedUid;
    static byte[] token;
    static Object im;
    static Method injectMethod;
    // un seul fil pour toutes les injections et les repetitions : l'ordre des touches est garanti
    static final ScheduledThreadPoolExecutor q = new ScheduledThreadPoolExecutor(1);
    static final Map<Integer, Held> held = new HashMap<Integer, Held>();   // touches enfoncees, lu sur q seulement

    static final class Held {
        final int code; final long down; int repeat; ScheduledFuture<?> next;
        Held(int code, long down) { this.code = code; this.down = down; }
    }

    public static void main(String[] args) throws Exception {
        allowedUid = Integer.parseInt(args[0]);
        if (args.length < 2 || args[1].length() < 32) { Log.e(TAG, "jeton absent"); System.exit(1); }
        token = args[1].getBytes(UTF8);
        // InputManager.getInstance() et injectInputEvent sont caches : reflexion, comme scrcpy sur API 30
        im = InputManager.class.getMethod("getInstance").invoke(null);
        injectMethod = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        ServerSocket ss = listen();
        if (ss == null) { Log.i(TAG, "un autre demon tient deja le port " + PORT); System.exit(0); }
        Log.i(TAG, "demon v" + VERSION + " pid " + Process.myPid() + ", uid autorise " + allowedUid);
        while (true) {
            final Socket s = ss.accept();
            new Thread(new Runnable() { public void run() { serve(s); } }, "client").start();
        }
    }

    // Port pris : si quelqu'un y ecoute (un autre demon), on s'efface ; sinon l'ancien meurt encore, on reessaie.
    static ServerSocket listen() throws IOException {
        for (int i = 0; i < 30; i++) {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);   // sous Linux, n'autorise pas deux ecoutes sur le meme port
            try {
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
                return ss;
            } catch (BindException e) {
                ss.close();
                if (answers()) return null;
                SystemClock.sleep(100);
            }
        }
        return null;
    }

    static boolean answers() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            return true;
        } catch (IOException e) { return false; }
        finally { try { s.close(); } catch (IOException ignored) { } }
    }

    static void serve(Socket s) {
        boolean allowed = false;
        try {
            int uid = peerUid(s);
            if (uid != allowedUid) { Log.w(TAG, "client refuse, uid " + uid); return; }
            s.setTcpNoDelay(true);
            s.setSoTimeout((int) HANDSHAKE_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
            OutputStream out = s.getOutputStream();
            String line = in.readLine();
            if (line == null || !line.startsWith("hello ")) { Log.w(TAG, "client refuse, pas de hello"); return; }
            String nd = nonce();
            out.write(("ok " + nd + " " + mac(token, "demon " + line.substring(6)) + "\n").getBytes(UTF8));
            out.flush();
            line = in.readLine();
            if (line == null || !line.startsWith("auth ") || !same(line.substring(5), mac(token, "client " + nd))) {
                Log.w(TAG, "client refuse, mauvais jeton");
                return;
            }
            out.write("ok\n".getBytes(UTF8));
            out.flush();
            s.setSoTimeout(0);
            allowed = true;
            while ((line = in.readLine()) != null) {
                String r = command(line);
                out.write((r + "\n").getBytes(UTF8));
                out.flush();
                if (line.equals("quit")) { Log.i(TAG, "quit"); System.exit(0); }
            }
        } catch (Exception e) {
            Log.w(TAG, "client", e);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
            // client parti touche enfoncee : on relache tout, sinon la touche reste bloquee
            if (allowed) q.execute(new Runnable() { public void run() { releaseAll(); } });
        }
    }

    // uid du client : la ligne de /proc/net/tcp(6) dont l'adresse locale est le port du client et l'adresse distante notre port
    static int peerUid(Socket s) {
        if (!s.getInetAddress().isLoopbackAddress()) return -1;
        int theirs = s.getPort(), ours = s.getLocalPort();
        for (String f : new String[] { "/proc/net/tcp6", "/proc/net/tcp" }) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(f));
                r.readLine();
                String l;
                while ((l = r.readLine()) != null) {
                    String[] p = l.trim().split("\\s+");
                    if (p.length > 7 && port(p[1]) == theirs && port(p[2]) == ours) return Integer.parseInt(p[7]);
                }
            } catch (Exception ignored) {
            } finally { if (r != null) try { r.close(); } catch (IOException ignored) { } }
        }
        return -1;
    }

    static final long HANDSHAKE_MS = 2000;
    static final java.security.SecureRandom random = new java.security.SecureRandom();

    static String nonce() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return hex(b);
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit(x >> 4 & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }

    static String mac(byte[] key, String msg) {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return hex(m.doFinal(msg.getBytes(UTF8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // comparaison en temps constant
    static boolean same(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(UTF8), b.getBytes(UTF8));
    }

    static int port(String addr) { return Integer.parseInt(addr.substring(addr.indexOf(':') + 1), 16); }

    static String command(String line) {
        int sp = line.indexOf(' ');
        String cmd = sp < 0 ? line : line.substring(0, sp), arg = sp < 0 ? "" : line.substring(sp + 1);
        try {
            if (cmd.equals("version")) return "ok " + VERSION;
            if (cmd.equals("quit")) return "ok";
            if (cmd.equals("text")) {
                final String t = unescape(arg);
                q.execute(new Runnable() { public void run() { typeText(t); } });
                return "ok";
            }
            final int code = Integer.parseInt(arg.trim());
            if (code <= 0 || code > 0xffff) return "err code";
            Runnable r;
            if (cmd.equals("key")) r = new Runnable() { public void run() { press(code); } };
            else if (cmd.equals("down")) r = new Runnable() { public void run() { down(code, HOLD_MAX_MS); } };
            else if (cmd.equals("up")) r = new Runnable() { public void run() { up(code); } };
            else if (cmd.equals("long")) r = new Runnable() { public void run() { down(code, LONG_MS); } };
            else return "err commande";
            q.execute(r);
            return "ok";
        } catch (NumberFormatException e) {
            return "err code";
        }
    }

    // DPAD et OK avec la source d'une telecommande (clavier + dpad), le reste en clavier comme "input keyevent"
    static int source(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER:
                return InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD;
            default: return InputDevice.SOURCE_KEYBOARD;
        }
    }

    static boolean inject(InputEvent e) {
        try { return (Boolean) injectMethod.invoke(im, e, 0); }   // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        catch (Exception x) { Log.w(TAG, "injection", x); return false; }
    }

    static boolean key(int action, int code, long down, int repeat, int flags) {
        return inject(new KeyEvent(down, SystemClock.uptimeMillis(), action, code, repeat, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, source(code)));
    }

    // les fonctions ci-dessous tournent toutes sur q
    static void press(int code) {
        up(code);
        long now = SystemClock.uptimeMillis();
        key(KeyEvent.ACTION_DOWN, code, now, 0, 0);
        key(KeyEvent.ACTION_UP, code, now, 0, 0);
    }

    // touche enfoncee, relachee seule au bout de holdMs ; nos propres repetitions arretent celles du systeme
    static void down(int code, long holdMs) {
        up(code);
        final Held h = new Held(code, SystemClock.uptimeMillis());
        held.put(code, h);
        key(KeyEvent.ACTION_DOWN, code, h.down, 0, 0);
        repeatLater(h, REPEAT_START_MS, holdMs);
    }

    static void repeatLater(final Held h, long delay, final long holdMs) {
        h.next = q.schedule(new Runnable() {
            public void run() {
                if (held.get(h.code) != h) return;
                if (SystemClock.uptimeMillis() - h.down >= holdMs) { up(h.code); return; }
                h.repeat++;
                key(KeyEvent.ACTION_DOWN, h.code, h.down, h.repeat, h.repeat == 1 ? KeyEvent.FLAG_LONG_PRESS : 0);
                repeatLater(h, REPEAT_EVERY_MS, holdMs);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    static void up(int code) {
        Held h = held.remove(code);
        if (h == null) return;
        if (h.next != null) h.next.cancel(false);
        key(KeyEvent.ACTION_UP, code, h.down, 0, 0);
    }

    static void releaseAll() {
        for (Integer c : held.keySet().toArray(new Integer[0])) up(c);
    }

    // texte : evenements du clavier virtuel ; un caractere sans touche (emoji...) part en ACTION_MULTIPLE
    static void typeText(String t) {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = 0; i < t.length(); ) {
            int n = Character.charCount(t.codePointAt(i));
            char[] c = t.substring(i, i + n).toCharArray();
            i += n;
            KeyEvent[] evs = map.getEvents(c);
            if (evs != null) for (KeyEvent e : evs) inject(e);
            else inject(new KeyEvent(SystemClock.uptimeMillis(), new String(c), KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
        }
    }

    static String escape(String s) { return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r"); }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                b.append(d == 'n' ? '\n' : d == 'r' ? '\r' : d);
            } else b.append(c);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- client (processus de Zen Home)

    static final long CONNECT_MS = 300, REPLY_MS = 1000, ADB_MS = 60_000, RETRY_MS = 30_000;
    static final Object lock = new Object();
    static Socket sock;
    static BufferedReader in;
    static OutputStream out;
    static volatile boolean ready, ensuring;
    static volatile long lastEnsure;
    static Context app;
    static Handler worker;
    static byte[] clientToken;

    /** Jeton du demon (32 caracteres hexa), tire une fois et garde dans les prefs privees. */
    static synchronized byte[] token() {
        if (clientToken != null) return clientToken;
        android.content.SharedPreferences p = app.getSharedPreferences("inject", Context.MODE_PRIVATE);
        String t = p.getString("token", null);
        if (t == null || t.length() < 32) {
            t = nonce() + nonce();
            p.edit().putString("token", t).commit();
        }
        clientToken = t.getBytes(UTF8);
        return clientToken;
    }

    /** Demon pret ? Sinon le lance par adb (fil de fond ; la premiere fois, la TV demande l'autorisation adb). */
    static void ensure(Context c) {
        app = c.getApplicationContext();
        synchronized (Inject.class) { if (ensuring) return; ensuring = true; }
        lastEnsure = SystemClock.elapsedRealtime();
        new Thread(new Runnable() {
            public void run() {
                try { start(); } catch (Exception e) { Log.w(TAG, "ensure", e); }
                finally { ensuring = false; }
            }
        }, "zen-inject-ensure").start();
    }

    static void start() {
        String v = call("version");
        if (("ok " + VERSION).equals(v)) { ready = true; return; }
        ready = false;
        if (v != null) call("quit");   // ancienne version qui repond encore
        close();
        String cmd = "p=$(pidof " + NAME + ") && kill $p && sleep 0.3; CLASSPATH='" + app.getApplicationInfo().sourceDir
                + "' nohup setsid app_process /system/bin --nice-name=" + NAME + " tv.zenhome.Inject "
                + Process.myUid() + " " + new String(token(), UTF8) + " </dev/null >/dev/null 2>&1 & sleep 1; echo lance";
        String r = AdbLocal.shell(app, cmd, (int) ADB_MS);
        if (r == null) {
            // le journal n'est lisible que par shell et le systeme, qui pourraient de toute facon remplacer le demon
            Log.w(TAG, "adb local indisponible, pas de demon ; a lancer a la main : " + cmd);
            return;
        }
        for (int i = 0; i < 50 && !ready; i++) {
            SystemClock.sleep(100);
            ready = ("ok " + VERSION).equals(call("version"));
        }
        Log.i(TAG, ready ? "demon pret" : "le demon ne repond pas");
    }

    static boolean ready() { return ready; }
    static boolean key(int keycode) { return send("key " + keycode); }
    static boolean keyDown(int keycode, boolean down) { return send((down ? "down " : "up ") + keycode); }
    static boolean longPress(int keycode) { return send("long " + keycode); }
    static boolean text(String s) { return send("text " + escape(s)); }

    // Fil principal : pas de reseau (StrictMode), la commande part sur un fil dedie et on rend l'etat connu.
    // Autre fil : envoi direct, quelques ms en local ; demon absent = connexion refusee tout de suite.
    static boolean send(final String line) {
        Looper main = Looper.getMainLooper();
        if (main != null && main == Looper.myLooper()) {
            if (!ready) { retry(); return false; }
            worker().post(new Runnable() { public void run() { if (call(line) == null) retry(); } });
            return true;
        }
        String r = call(line);
        if (r == null) retry();
        return r != null && r.startsWith("ok");
    }

    static void retry() {
        if (app != null && !ensuring && SystemClock.elapsedRealtime() - lastEnsure > RETRY_MS) ensure(app);
    }

    static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("zen-inject");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }

    // une connexion gardee ouverte ; une connexion morte (demon relance) est refaite une fois
    static String call(String line) {
        synchronized (lock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean fresh = sock == null;
                try {
                    if (fresh) connect();
                    out.write((line + "\n").getBytes(UTF8));
                    out.flush();
                    String r = in.readLine();
                    if (r == null) throw new IOException("connexion fermee");
                    return r;
                } catch (SocketTimeoutException e) {
                    close();   // pas de nouvel essai : la commande est peut-etre partie
                    break;
                } catch (IOException e) {
                    close();
                    if (fresh) break;
                }
            }
            ready = false;
            return null;
        }
    }

    static void connect() throws IOException {
        if (app == null) throw new IOException("ensure() pas encore appele");
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress("127.0.0.1", PORT), (int) CONNECT_MS);
            s.setSoTimeout((int) REPLY_MS);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
            OutputStream o = s.getOutputStream();
            // le demon prouve qu'il connait le jeton avant qu'on lui envoie quoi que ce soit, puis nous de meme
            String nc = nonce();
            o.write(("hello " + nc + "\n").getBytes(UTF8));
            o.flush();
            String l = r.readLine();
            String[] p = l != null ? l.split(" ") : new String[0];
            if (p.length != 3 || !p[0].equals("ok") || !same(p[2], mac(token(), "demon " + nc)))
                throw new IOException("demon inconnu sur le port " + PORT);
            o.write(("auth " + mac(token(), "client " + p[1]) + "\n").getBytes(UTF8));
            o.flush();
            if (!"ok".equals(r.readLine())) throw new IOException("jeton refuse");
            in = r;
            out = o;
            sock = s;
            ready = true;   // le demon connait le jeton : c'est le notre (lance par ensure, ou a la main par adb)
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    static void close() {
        if (sock != null) try { sock.close(); } catch (IOException ignored) { }
        sock = null; in = null; out = null;
    }
}
