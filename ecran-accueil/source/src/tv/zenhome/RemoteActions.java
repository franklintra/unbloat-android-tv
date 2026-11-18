package tv.zenhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ce que la telecommande de l'iPhone (Companion.java) fait faire a la TV : les boutons et les gestes deviennent
 * de vraies touches (Inject.java, demon shell), le texte passe par le service d'accessibilite (Keys), le volume
 * par AudioManager comme l'AirPlay.
 *
 * Veille (STR) : tout bouton recu TV en veille la reveille au lieu d'agir, comme la telecommande TCL. Le reveil
 * part en touche WAKEUP injectee, reessayee toutes les 500 ms (TCL perd celle qui suit de pres une mise en
 * veille), puis WakeActivity si la TV dort encore. Marche et Veille de l'iPhone injectent la touche Marche (26) :
 * meme chemin que la vraie telecommande, donc la musique AirPlay garde la logique d'AudioOnly.onScreenOff.
 *
 * Audio seul : les touches injectees ne passent pas par le filtre de Keys, d'ou l'appel a AudioOnly.onKey() a
 * chaque action. TCL rallume l'image pour toute touche recue en mode Audio seul, injectee comprise.
 *
 * Fils d'execution : button, swipe, tap, text, media, setVolume, launchApp, showPin, hidePin et onConnection
 * arrivent sur un seul fil "companion-actions", dans l'ordre de reception (une methode lente retarde les
 * suivantes, sans bloquer le protocole ; au-dela de 64 actions en attente, les nouvelles sont perdues).
 * screenOn, currentVolume et apps sont appelees sur le fil de la connexion et doivent repondre tout de suite.
 * onAccept est appelee sur le fil d'acceptation ; onAccessibility et onScreen sur le fil principal (Keys).
 */
final class RemoteActions {

    static final String TAG = "ZenRemote";

    // Codes _hidC des boutons de la telecommande (valeurs du protocole, enums.py HidCommand)
    static final int UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4;
    static final int MENU = 5;              // "retour" (fleche gauche / Menu de la Siri Remote)
    static final int SELECT = 6;            // clic central
    static final int HOME = 7;              // bouton TV : accueil (un appui long = centre de controle sur Apple TV)
    static final int VOLUME_UP = 8, VOLUME_DOWN = 9;
    static final int SIRI = 10;             // jamais transmis : pas de relais de la voix
    static final int SCREENSAVER = 11;
    static final int SLEEP = 12, WAKE = 13; // veille / reveil explicites
    static final int PLAY_PAUSE = 14;
    static final int CHANNEL_UP = 15, CHANNEL_DOWN = 16, GUIDE = 17;
    static final int MUTE = 18;             // iOS 26 envoie la sourdine du centre de controle en 18 (PageUp HID)
    static final int POWER = 19;            // iOS 26 envoie le bouton marche/arret en 19 (PageDown HID)

    // Commandes media (MediaControlCommand)
    static final int MEDIA_PLAY = 1, MEDIA_PAUSE = 2, MEDIA_NEXT = 3, MEDIA_PREVIOUS = 4, MEDIA_SKIP_BY = 7;

    static final long CONN_LOCK_MS = 10 * 60_000L, ACCEPT_LOCK_MS = 30_000L, APPS_TTL_MS = 60_000L;
    static final long WAKE_RETRY_MS = 500, WAKE_DEDUPE_MS = 1500;
    static final int WAKE_TRIES = 4;   // WAKEUP, 3 nouveaux essais, puis WakeActivity

    static final Handler main = new Handler(Looper.getMainLooper());
    // envois vers les iPhones demandes depuis le fil principal (pas de reseau sur ce fil), dans l'ordre
    static final ExecutorService toPhones = Executors.newSingleThreadExecutor(Companion.daemon("companion-ui"));
    static PowerManager.WakeLock connLock, acceptLock;
    static int open;   // connexions authentifiees ouvertes, lu et ecrit sur le fil des actions
    static volatile long lastWake = -WAKE_DEDUPE_MS;

    private RemoteActions() { }

    static Context ctx() { return Companion.ctx; }

    // ------------------------------------------------------------------ boutons et gestes

    /** Touche Android d'un bouton _hidC, ou 0 (bouton traite a part ou ignore). */
    static int keycode(int hid) {
        switch (hid) {
            case UP: return KeyEvent.KEYCODE_DPAD_UP;
            case DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
            case LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case SELECT: return KeyEvent.KEYCODE_DPAD_CENTER;
            case MENU: return KeyEvent.KEYCODE_BACK;
            case HOME: return KeyEvent.KEYCODE_HOME;
            case VOLUME_UP: return KeyEvent.KEYCODE_VOLUME_UP;
            case VOLUME_DOWN: return KeyEvent.KEYCODE_VOLUME_DOWN;
            case MUTE: return KeyEvent.KEYCODE_VOLUME_MUTE;
            case PLAY_PAUSE: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case CHANNEL_UP: return KeyEvent.KEYCODE_CHANNEL_UP;
            case CHANNEL_DOWN: return KeyEvent.KEYCODE_CHANNEL_DOWN;
            case GUIDE: return KeyEvent.KEYCODE_GUIDE;   // guide TV de l'entree TCL ; sans effet ailleurs
            default: return 0;   // SIRI, SCREENSAVER et codes inconnus
        }
    }

    /**
     * Bouton appuye (down) ou relache (!down). Chaque appui donne down puis up ; la duree entre les deux est celle
     * du doigt (appui long possible). Un relachement sans appui connu arrive en down+up colles. Le clic central
     * en double (bouton SELECT et tape sur le pave tactile) n'est transmis qu'une fois. Codes : constantes ci-dessus,
     * d'autres valeurs inconnues peuvent arriver.
     */
    static void button(int hidCommand, boolean down) {
        Log.i(TAG, "bouton " + hidCommand + (down ? " appuye" : " relache"));
        if (down) used();
        boolean on = screenOn();
        if (hidCommand == WAKE) { if (down) wake(); return; }
        if (hidCommand == SLEEP) { if (down && on) sleep(); return; }
        if (!on) {
            // TV en veille : un bouton (Marche compris) la reveille, comme la telecommande TCL ; le relachement est perdu
            if (down) wake();
            return;
        }
        if (hidCommand == POWER) { if (down) sleep(); return; }
        if (hidCommand == HOME) { homeButton(down); return; }
        int k = keycode(hidCommand);
        if (k != 0) Inject.keyDown(k, down);   // le demon repete tant que le doigt reste : appui long possible
    }

    /** Appui perdu (connexion ou session TV Remote terminee doigt pose) : relache sans rien declencher. */
    static void release(int hidCommand) {
        if (hidCommand == HOME) {
            main.post(new Runnable() { public void run() { main.removeCallbacks(homeLong); homeLongFired = true; } });
            return;
        }
        int k = keycode(hidCommand);
        if (k != 0) Inject.keyDown(k, false);
    }

    static final long HOME_LONG_MS = 500;
    static boolean homeLongFired;   // fil principal seulement
    static final Runnable homeLong = new Runnable() {
        public void run() {
            homeLongFired = true;
            Log.i(TAG, "accueil : appui long");
            // demon d'injection absent : l'accessibilite sait aussi aller a l'accueil
            if (!Inject.key(KeyEvent.KEYCODE_HOME) && Keys.self != null) Keys.self.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    };

    /**
     * Bouton TV de l'iPhone, comme la touche Reglages TCL (Keys) : clic = barre des reglages par-dessus l'appli
     * (PanelActivity, un second clic la referme), appui long (HOME_LONG_MS) = ecran d'accueil.
     */
    static void homeButton(final boolean down) {
        // tout sur le fil principal, comme homeLong : un relachement a HOME_LONG_MS pile ne fait pas clic et accueil
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() { public void run() { homeButton(down); } });
            return;
        }
        if (down) {
            homeLongFired = false;
            main.removeCallbacks(homeLong);
            main.postDelayed(homeLong, HOME_LONG_MS);
            return;
        }
        main.removeCallbacks(homeLong);
        if (homeLongFired) return;
        Context c = ctx();
        if (c == null) return;
        Log.i(TAG, "accueil : clic, barre des reglages");
        try {
            c.startActivity(new Intent().setComponent(new android.content.ComponentName(c, "tv.zenhome.PanelActivity"))
                    .putExtra(PanelActivity.EXTRA_DOCK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { Log.w(TAG, "barre des reglages", e); }
    }

    /**
     * Glissement sur le pave tactile : une fleche (UP, DOWN, LEFT, RIGHT). Un long glissement donne plusieurs appels
     * (un par 350/1000 du pave, 10 au plus) ; un doigt garde au bord repete la fleche (apres 400 ms, puis toutes les
     * 120 ms, 15 s au plus) jusqu'au relachement.
     */
    static void swipe(int direction) {
        Log.i(TAG, "glissement " + direction);
        press(keycode(direction));
    }

    /** Tape (ou clic) sur le pave tactile : equivaut a SELECT (un clic complet, pas de down/up separes). */
    static void tap() {
        Log.i(TAG, "tape");
        press(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    /** Commande media (MEDIA_*) ; seconds sert a MEDIA_SKIP_BY (negatif = en arriere), 0 sinon. */
    static void media(int command, double seconds) {
        Log.i(TAG, "media " + command + (command == MEDIA_SKIP_BY ? " " + seconds + " s" : ""));
        int k;
        switch (command) {
            case MEDIA_PLAY: k = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case MEDIA_PAUSE: k = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case MEDIA_NEXT: k = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case MEDIA_PREVIOUS: k = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case MEDIA_SKIP_BY:
                if (seconds == 0) return;
                k = seconds > 0 ? KeyEvent.KEYCODE_MEDIA_FAST_FORWARD : KeyEvent.KEYCODE_MEDIA_REWIND;
                break;
            default: return;
        }
        press(k);
    }

    /** Appui complet ; TV en veille, on la reveille a la place. */
    static void press(int keycode) {
        if (keycode == 0) return;
        used();
        if (!screenOn()) { wake(); return; }
        Inject.key(keycode);
    }

    /** Toute action de l'iPhone : la telecommande sert (Audio seul, rendormissement), on garde la TV eveillee. */
    static void used() {
        AudioOnly.onKey();
        if (open > 0) hold(CONN_LOCK_MS);
    }

    // ------------------------------------------------------------------ veille et reveil

    /** Ecran allume ? Repond a FetchAttentionState (Awake ou Asleep). */
    static boolean screenOn() {
        Context c = ctx();
        if (c == null) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    /** Reveil explicite : WAKEUP injecte, reessaye, puis WakeActivity (le chemin "turn screen on" de l'AirPlay). */
    static void wake() {
        final Context c = ctx();
        if (c == null || screenOn()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWake < WAKE_DEDUPE_MS) return;   // plusieurs boutons d'affilee : un seul reveil
        lastWake = now;
        hold(ACCEPT_LOCK_MS);   // la STR ne doit pas reprendre la puce pendant le reveil
        Log.i(TAG, "TV en veille : reveil");
        boolean sent = Inject.ready() && Inject.key(KeyEvent.KEYCODE_WAKEUP);
        if (!sent) wakeActivity(c);
        for (int i = 1; i <= WAKE_TRIES; i++) {
            final int n = i;
            main.postDelayed(new Runnable() {
                public void run() {
                    if (screenOn()) return;
                    if (n < WAKE_TRIES && Inject.ready()) { Log.i(TAG, "reveil : nouvel essai"); Inject.key(KeyEvent.KEYCODE_WAKEUP); }
                    else { Log.i(TAG, "reveil : WakeActivity"); wakeActivity(c); }
                }
            }, WAKE_RETRY_MS * i);
        }
    }

    static void wakeActivity(Context c) {
        try {
            c.startActivity(new Intent(c, WakeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) { Log.w(TAG, "WakeActivity", e); }
    }

    /**
     * Marche ou Veille de l'iPhone, TV allumee : la touche Marche (26) injectee prend le chemin de la vraie
     * telecommande (veille rapide, AudioOnly.onScreenOff garde la musique). Sans demon : verrouillage par
     * l'accessibilite, qui endort aussi la TV.
     */
    static void sleep() {
        release();   // le verrou de la connexion ne doit pas retenir la veille rapide
        if (Inject.ready() && Inject.key(KeyEvent.KEYCODE_POWER)) { Log.i(TAG, "veille : touche Marche"); return; }
        Keys k = Keys.self;
        boolean ok = k != null && Build.VERSION.SDK_INT >= 28 && k.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        Log.i(TAG, "veille : " + (ok ? "verrouillage de l'ecran" : "impossible (ni demon ni accessibilite)"));
    }

    // ------------------------------------------------------------------ verrous de reveil

    /** Companion.acceptLoop : un iPhone se connecte, peut-etre a la TV en veille (le Wi-Fi a reveille la puce). */
    static synchronized void onAccept() {
        Context c = ctx();
        if (c == null) return;
        if (acceptLock == null) {
            acceptLock = ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zenhome:companion-accept");
            acceptLock.setReferenceCounted(false);
        }
        acceptLock.acquire(ACCEPT_LOCK_MS);   // le temps du pair-verify
    }

    static synchronized void hold(long ms) {
        Context c = ctx();
        if (c == null) return;
        if (connLock == null) {
            connLock = ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zenhome:companion-conn");
            connLock.setReferenceCounted(false);
        }
        connLock.acquire(ms);
    }

    static synchronized void release() {
        if (connLock != null && connLock.isHeld()) connLock.release();
        if (acceptLock != null && acceptLock.isHeld()) acceptLock.release();
    }

    /**
     * Connexion authentifiee ouverte (true) ou fermee (false), une fois par connexion. iOS garde la connexion
     * longtemps : verrou PARTIAL limite a CONN_LOCK_MS, prolonge a chaque action, rendu a la derniere fermeture.
     * Le verrou ne suffit pas TV en veille : le STR_Worker TCL rendort la puce ~3 s apres le pair-verify meme
     * verrou tenu (vu le 2026-11-18), et les messages suivants de l'iPhone (_sessionStart, boutons) ne la
     * reveillent pas. Une connexion authentifiee TV en veille allume donc l'ecran tout de suite, comme une
     * Apple TV quand on ouvre la Telecommande du Centre de controle.
     */
    static void onConnection(boolean isOpen) {
        Log.i(TAG, "connexion " + (isOpen ? "ouverte" : "fermee"));
        open = Math.max(0, open + (isOpen ? 1 : -1));
        if (isOpen) refreshField();
        if (open > 0) hold(CONN_LOCK_MS);
        else release();
        if (isOpen && !screenOn()) { Log.i(TAG, "iPhone connecte TV en veille"); wake(); }
    }

    /** Keys, SCREEN_ON / SCREEN_OFF : etat pousse aux iPhones ; ecran eteint, la TV peut dormir. */
    static void onScreen(final boolean on) {
        toPhones.execute(new Runnable() { public void run() { Companion.systemStatus(on); } });
        if (!on) release();
        else refreshField();
    }

    // ------------------------------------------------------------------ texte

    static volatile AccessibilityNodeInfo field;   // champ de saisie qui a le focus, null sinon
    static volatile String typed = "";   // dernier texte envoye par injection (repli sans accessibilite)

    /**
     * Keys.onAccessibilityEvent : un champ de saisie prend ou perd le focus, ou change de texte. Ouvre ou ferme
     * le clavier de l'iPhone (Companion.keyboard), avec le texte actuel du champ.
     */
    static void onAccessibility(AccessibilityService s, AccessibilityEvent e) {
        if (!Companion.running()) return;
        int t = e.getEventType();
        AccessibilityNodeInfo n;
        try {
            if (t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                AccessibilityNodeInfo f = field;
                n = e.getSource();
                if (f != null && f.equals(n)) keyboard(true, textOf(n));   // garde le texte a jour
                return;
            }
            n = t == AccessibilityEvent.TYPE_VIEW_FOCUSED ? e.getSource() : null;
            if (n == null || !n.isEditable()) n = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        } catch (RuntimeException x) {
            return;   // fenetre fermee entre l'evenement et la lecture
        }
        focusChanged(n);
    }

    /**
     * Relit le champ qui a le focus, sans attendre un evenement : un iPhone se connecte ou l'ecran se rallume
     * alors qu'un champ a deja le focus (aucun TYPE_VIEW_FOCUSED ne viendra).
     */
    static void refreshField() {
        main.post(new Runnable() {
            public void run() {
                Keys k = Keys.self;
                if (k == null || !Companion.running()) return;
                AccessibilityNodeInfo n;
                try { n = k.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); } catch (RuntimeException x) { return; }
                focusChanged(n);
            }
        });
    }

    static void focusChanged(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo f = field;
        if (n != null && n.isEditable()) {
            if (n.equals(f)) return;
            if (f != null) keyboard(false, null);   // un autre champ : on ferme puis on rouvre avec son texte
            field = n;
            String text = textOf(n);
            typed = text;
            Log.i(TAG, "champ de saisie : clavier de l'iPhone ouvert");
            keyboard(true, text);
        } else if (f != null) {
            field = null;
            Log.i(TAG, "champ de saisie quitte : clavier de l'iPhone ferme");
            keyboard(false, null);
        }
    }

    static void keyboard(final boolean focused, final String text) {
        toPhones.execute(new Runnable() { public void run() { Companion.keyboard(focused, text); } });
    }

    static String textOf(AccessibilityNodeInfo n) {
        if (n == null || n.getText() == null || n.isPassword()) return "";
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) return "";
        return n.getText().toString();
    }

    /**
     * Texte du champ apres une frappe sur le clavier de l'iPhone : la valeur complete (1024 caracteres au plus), a
     * mettre a la place du contenu actuel. Le clavier n'apparait sur l'iPhone qu'apres Companion.keyboard(true).
     * Touche Retour de l'iPhone (texte qui finit par un saut de ligne) : action "Entree" du champ.
     */
    static void text(String fullText) {
        Log.i(TAG, "texte (" + fullText.length() + " caracteres)");
        used();
        boolean enter = fullText.endsWith("\n");
        String t = fullText.replace("\n", "");
        AccessibilityNodeInfo n = null;
        Keys k = Keys.self;
        try {
            n = k != null ? k.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
        } catch (RuntimeException ignored) { }
        if (n != null && n.isEditable()) {
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t);
            if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {   // TextView met le curseur a la fin
                typed = t;
                if (enter) enter(n);
                return;
            }
        }
        // repli : efface ce qui differe du dernier texte envoye, puis tape la suite
        String old = typed;
        int common = 0;
        while (common < old.length() && common < t.length() && old.charAt(common) == t.charAt(common)) common++;
        for (int i = common; i < old.length(); i++) Inject.key(KeyEvent.KEYCODE_DEL);
        if (common < t.length()) Inject.text(t.substring(common));
        typed = t;
        if (enter) Inject.key(KeyEvent.KEYCODE_ENTER);
    }

    static void enter(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT >= 30 && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) return;
        Inject.key(KeyEvent.KEYCODE_ENTER);
    }

    // ------------------------------------------------------------------ volume

    /**
     * Curseur de volume de l'iPhone : niveau vise de 0 a 1, et up = plus fort que le niveau precedent. Pilote le
     * vrai volume de la TV (STREAM_MUSIC), comme le curseur AirPlay.
     */
    static void setVolume(float level, boolean up) {
        Log.i(TAG, "volume " + level + (up ? " (plus fort)" : " (moins fort)"));
        Context c = ctx();
        if (c == null) return;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, Math.round(level * max))), 0);
        } catch (Exception e) { Log.w(TAG, "volume", e); }
    }

    /** Volume actuel de 0 a 1, ou -1 si inconnu (Companion garde alors son propre niveau, comme le Python). */
    static float currentVolume() {
        Context c = ctx();
        if (c == null) return -1;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : -1;
        } catch (Exception e) { return -1; }
    }

    // ------------------------------------------------------------------ appairage

    /** Code a 4 chiffres a afficher en grand sur la TV pendant l'appairage, jusqu'a hidePin(). */
    static void showPin(String pin) {
        Log.i(TAG, "code d'appairage affiche");
        Context c = ctx();
        if (c == null) return;
        PinActivity.wanted = pin;
        try {
            c.startActivity(new Intent(c, PinActivity.class).putExtra(PinActivity.EXTRA_PIN, pin)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) { Log.w(TAG, "PinActivity", e); }
    }

    /** Fin de l'appairage (reussi, refuse ou abandonne) : retirer le code de l'ecran. */
    static void hidePin() {
        Log.i(TAG, "code d'appairage retire");
        PinActivity.wanted = null;
        main.post(new Runnable() { public void run() { PinActivity.close(); } });
    }

    // ------------------------------------------------------------------ applis

    static volatile Map<String, String> apps = Collections.emptyMap();
    static volatile long appsAt = -APPS_TTL_MS;

    /** Applis lancables (leanback), identifiant (paquet) -> nom ; liste gardee APPS_TTL_MS, refaite en fond. */
    static Map<String, String> apps() {
        long now = SystemClock.elapsedRealtime();
        if (now - appsAt >= APPS_TTL_MS) {
            appsAt = now;
            if (apps.isEmpty()) loadApps();   // premiere fois : quelques dizaines de ms
            else new Thread(new Runnable() { public void run() { loadApps(); } }, "companion-apps").start();
        }
        return apps;
    }

    static void loadApps() {
        Context c = ctx();
        if (c == null) return;
        try {
            PackageManager pm = c.getPackageManager();
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
            LinkedHashMap<String, String> m = new LinkedHashMap<>();
            for (ResolveInfo r : l) {
                String p = r.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || m.containsKey(p)) continue;
                m.put(p, String.valueOf(r.loadLabel(pm)));
            }
            apps = m;
        } catch (Exception e) { Log.w(TAG, "applis", e); }
    }

    /** Lancer une appli (identifiant de apps()) ou ouvrir une URL ; l'un des deux est null. */
    static void launchApp(String bundleId, String url) {
        Log.i(TAG, "lancer " + (bundleId != null ? bundleId : url));
        used();
        Context c = ctx();
        if (c == null) return;
        if (!screenOn()) wake();
        Intent i = null;
        if (bundleId != null) {
            PackageManager pm = c.getPackageManager();
            i = pm.getLeanbackLaunchIntentForPackage(bundleId);
            if (i == null) i = pm.getLaunchIntentForPackage(bundleId);
        } else if (url != null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        }
        if (i == null) { Log.w(TAG, "rien a lancer"); return; }
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Log.w(TAG, "lancement", e); }
    }
}
