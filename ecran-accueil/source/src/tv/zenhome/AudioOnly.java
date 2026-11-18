package tv.zenhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/**
 * Musique AirPlay ecran eteint, avec le mode "Audio seul" de TCL.
 *
 * Quand un iPhone reveille la TV en veille pour y jouer du son, AirPlay.wakeScreen() arme ce mode ; des que
 * l'ecran s'allume, on demande a TCL son mode Audio seul (menu Son, ShowWindowService Type=AudioOnly) : un
 * compte a rebours de 10 s, puis TCL coupe le retroeclairage et leve son drapeau audio seul. Avec ce drapeau,
 * le TVKitService de TCL rallume l'image a la premiere touche de la telecommande : c'est le geste "je veux
 * l'ecran". Le compte a rebours est impose : l'appel direct (TTvFunctionManager.setPowerBacklight) est refuse
 * a une appli non systeme (tos_ret=1 apres 10 s de blocage), et les recepteurs TCL qui le font sont proteges
 * par signature.
 *
 * Si personne n'a touche la telecommande, la TV se rendort GRACE_MS apres la fin de la musique : on baisse
 * sleep_timeout (reglage Secure, d'ou WRITE_SECURE_SETTINGS accorde une fois par adb) le temps qu'elle
 * s'endorme, puis on remet la valeur d'origine (sauvee dans les prefs, restauree aussi a la premiere touche,
 * au reveil et au demarrage du service). Baisser screen_off_timeout ne suffit pas : la TV lance alors
 * l'economiseur d'ecran (Dreaming) et ne dort qu'au bout de sleep_timeout, 4 h ici. Au bout de sleep_timeout,
 * TCL n'endort pas directement : son TimerManagerBroadcastReceiver affiche "extinction dans 60 s" (invisible,
 * retroeclairage coupe), puis met la TV en veille rapide et baisse lui-meme le drapeau audio seul.
 * Fin de la musique -> veille : GRACE_MS + 5 s + 60 s. Le bouton
 * Marche de la telecommande rallume l'image au reveil suivant (PowerKeyBroadcastReceiver de com.tcl.tv).
 */
final class AudioOnly {

    static final String TAG = "ZenAirPlay";
    static final String SLEEP_TIMEOUT = "sleep_timeout";   // Settings.Secure, cache dans le SDK
    static final long GRACE_MS = 60_000, SLEEP_TIMEOUT_MS = 5_000, ARM_MS = 20_000, SETTLE_MS = 2_000, AGAIN_MS = 30_000;

    static final Handler h = new Handler(Looper.getMainLooper());
    static Context ctx;
    static volatile boolean armed;   // AirPlay vient de reveiller la TV : ecran a eteindre des qu'il s'allume
    static volatile boolean dark;    // retroeclairage coupe par nous, personne n'a encore touche la telecommande
    static volatile boolean asked;   // menu Audio seul TCL deja demande (compte a rebours ou drapeau leve)
    static long lastOff = -AGAIN_MS;   // dernier SCREEN_OFF (elapsedRealtime)

    static final Runnable disarm = new Runnable() { public void run() { armed = false; } };
    static final Runnable goDark = new Runnable() {
        public void run() {
            if (!dark || MirrorActivity.picture) return;
            asked = true;
            try {
                ctx.startService(new Intent("com.tcl.settings.SHOW_WINDOW").setPackage("com.tcl.settings").putExtra("Type", "AudioOnly"));
            } catch (Exception e) { Log.w(TAG, "mode audio seul TCL", e); }
        }
    };
    static final Runnable sleep = new Runnable() {
        public void run() {
            if (!dark || AirPlay.busy() || Mirror.Music.active || appMusic) return;
            Log.i(TAG, "audio seul : fin de la musique, la TV se rendort");
            SharedPreferences p = prefs();
            try {
                int t = Settings.Secure.getInt(ctx.getContentResolver(), SLEEP_TIMEOUT, -1);
                if (!p.contains(SLEEP_TIMEOUT)) p.edit().putInt(SLEEP_TIMEOUT, t).commit();
                Settings.Secure.putInt(ctx.getContentResolver(), SLEEP_TIMEOUT, (int) SLEEP_TIMEOUT_MS);
            } catch (Exception e) {
                // sans WRITE_SECURE_SETTINGS : la TV reste allumee, image coupee, jusqu'a sa propre mise en veille
                Log.w(TAG, "mise en veille : adb shell pm grant tv.zenhome android.permission.WRITE_SECURE_SETTINGS", e);
                p.edit().remove(SLEEP_TIMEOUT).commit();
            }
        }
    };

    static SharedPreferences prefs() { return ctx.getSharedPreferences("airplay", Context.MODE_PRIVATE); }

    /** AirPlay.wakeScreen, juste avant d'allumer la TV. */
    static void arm(Context c) {
        ctx = c.getApplicationContext();
        if (MirrorActivity.picture) return;   // video ou recopie : on veut l'image
        h.post(new Runnable() { public void run() {
            armed = true;
            h.removeCallbacks(disarm);
            h.postDelayed(disarm, ARM_MS);
        } });
    }

    /** Keys, ACTION_SCREEN_ON. */
    static void onScreenOn(Context c) {
        ctx = c.getApplicationContext();
        offUntil = 0;   // rallumee a la main apres un double Marche : la musique peut de nouveau la reveiller
        restoreTimeout(c);
        if (!armed) return;
        armed = false;
        h.removeCallbacks(disarm);
        if (MirrorActivity.picture) return;
        dark = true;
        asked = false;
        Log.i(TAG, "audio seul : l'ecran s'eteint dans 10 s, une touche le rallume");
        h.postDelayed(goDark, SETTLE_MS);   // apres la reprise TCL, qui fermerait le compte a rebours
    }

    /** Keys, ACTION_SCREEN_OFF : la TV dort, on rend la main a TCL pour le prochain reveil. */
    static void onScreenOff(Context c) {
        ctx = c.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        boolean again = now - lastOff < AGAIN_MS;
        lastOff = now;
        // Marche pendant la musique : on garde le son et on coupe seulement l'image. La touche est traitee avant
        // l'accessibilite (impossible a bloquer) et la veille rapide (STR) suit SCREEN_OFF de ~4 s : on rallume
        // tout de suite, en mode Audio seul. Deux appuis a moins de AGAIN_MS eteignent vraiment la TV.
        // (En Audio seul, Marche rallume d'abord l'image, comme toute touche TCL.)
        // Juste apres SCREEN_OFF, TCL finit sa mise en veille et ignore le premier reveil (WakeActivity mise en
        // pause) : on reessaie pendant 2 s. Si la STR passe quand meme, Mirror.Music rallume au paquet suivant.
        boolean music = Mirror.Music.active || appMusic || AirPlay.busy();
        h.removeCallbacks(rewake);
        if (music && !again) {
            Log.i(TAG, "Marche pendant la musique : image coupee, le son continue");
            AirPlay.wakeScreen(ctx);
            for (long d = 500; d <= 2000; d += 500) h.postDelayed(rewake, d);
        } else if (music) {
            Log.i(TAG, "Marche deux fois : la TV s'eteint");
            offUntil = now + OFF_MS;
        }
        h.removeCallbacks(goDark);
        h.removeCallbacks(sleep);
        dark = false;
        restoreTimeout(c);
    }

    // eteinte expres pendant la musique : pas de reveil automatique avant offUntil. Leve a la fin de la musique ou
    // quand on rallume la TV ; OFF_MS n'est qu'un filet si aucune fin n'arrive (avant : 2 min, puis la TV se rallumait)
    static final long OFF_MS = 6 * 3600_000L;
    static volatile long offUntil;

    static final Runnable rewake = new Runnable() {
        public void run() { if (SystemClock.elapsedRealtime() >= offUntil) AirPlay.wakeScreen(ctx); }
    };

    /** Mirror.Music, paquet son recu TV en veille (STR reveillee par le Wi-Fi) : on rallume, sauf extinction voulue. */
    static boolean mayWake() { return SystemClock.elapsedRealtime() >= offUntil; }

    /** Keys.onKeyEvent : la telecommande sert, TCL rallume l'image ; on ne rendormira pas la TV. */
    static void onKey() {
        armed = false;
        if (ctx != null) restoreTimeout(ctx);
        if (!dark) return;
        dark = false;
        h.removeCallbacks(goDark);
        h.removeCallbacks(sleep);
        Log.i(TAG, "audio seul : touche, ecran rallume");
    }

    /**
     * MirrorActivity.show (recopie d'ecran, video d'une appli) : il faut l'image. Si la TV est en Audio seul, on
     * rallume le retroeclairage. Seul le systeme le peut (TTvFunctionManager.setPowerBacklight refuse une appli
     * non systeme) ; TclPhoneWindowManager le fait pour toute touche recue en mode Audio seul, y compris une
     * touche injectee : un Retour du service d'accessibilite suffit, et TCL le consomme. Pendant le compte a
     * rebours (drapeau pas encore leve), ce Retour ferme le compte a rebours. Rend true si une touche a ete injectee.
     */
    static boolean showPicture(Context c) {
        ctx = c.getApplicationContext();
        armed = false;
        h.removeCallbacks(disarm);
        if (!dark) return false;
        dark = false;
        h.removeCallbacks(goDark);
        h.removeCallbacks(sleep);
        restoreTimeout(ctx);
        if (!asked) { Log.i(TAG, "video : mode audio seul annule avant le compte a rebours"); return false; }
        asked = false;
        Keys k = Keys.self;
        boolean ok = k != null && k.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        Log.i(TAG, "video : sortie du mode audio seul TCL" + (ok ? "" : " impossible (service d'accessibilite absent)"));
        return ok;
    }

    static void sessionStarted() { h.removeCallbacks(sleep); if (ctx != null) restoreTimeout(ctx); }
    static void sessionEnded() { offUntil = 0; h.removeCallbacks(sleep); if (dark) h.postDelayed(sleep, GRACE_MS); }

    // ---- musique d'une autre appli (Spotify Connect...) lancee pendant la veille

    static AudioManager.AudioPlaybackCallback playback;
    static boolean appMusic;   // une appli (pas l'AirPlay de Zen Home) joue de la musique

    static final Runnable checkPlayback = new Runnable() {
        public void run() {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            // le son de la recopie ou de la video AirPlay joue dans Zen Home : ce n'est pas la musique d'une appli
            boolean on = am.isMusicActive() && !Mirror.Music.active && !AirPlay.busy() && !MirrorActivity.picture;
            if (on == appMusic) return;
            appMusic = on;
            if (on) {
                Log.i(TAG, "musique d'une appli");
                sessionStarted();
                AirPlay.wakeScreen(ctx);   // TV en veille (Spotify Connect) : allumee en mode Audio seul
            } else {
                Log.i(TAG, "fin de la musique d'une appli");
                sessionEnded();
            }
        }
    };

    /**
     * Keys.onServiceConnected. Spotify Connect (enceinte "Franklin") joue dans l'appli Spotify de la TV : si on
     * l'envoie sur la TV en veille, l'appli demarre le son mais rien n'allume la TV. On ecoute donc les lectures
     * de toutes les applis et on reveille la TV comme pour AirPlay. Il faut aussi sortir Spotify de Doze, sinon
     * il ne recoit rien en veille : adb shell dumpsys deviceidle whitelist +com.spotify.tv.android
     */
    static void watchPlayback(Context c) {
        ctx = c.getApplicationContext();
        if (playback != null || Build.VERSION.SDK_INT < 26) return;
        playback = new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> l) {
                h.removeCallbacks(checkPlayback);
                h.post(checkPlayback);
                h.postDelayed(checkPlayback, 1500);   // isMusicActive suit l'arret de la piste avec retard
            }
        };
        try { ((AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE)).registerAudioPlaybackCallback(playback, h); }
        catch (Exception e) { Log.w(TAG, "suivi des lectures", e); playback = null; }
    }

    /** Remet le delai de veille d'origine, s'il a ete baisse. */
    static void restoreTimeout(Context c) {
        ctx = c.getApplicationContext();
        SharedPreferences p = prefs();
        if (p.contains("timeout")) p.edit().remove("timeout").commit();   // 2.15 : screen_off_timeout, deja remis
        if (!p.contains(SLEEP_TIMEOUT)) return;
        try {
            int t = p.getInt(SLEEP_TIMEOUT, -1);
            if (t < 0) Settings.Secure.putString(ctx.getContentResolver(), SLEEP_TIMEOUT, null);
            else Settings.Secure.putInt(ctx.getContentResolver(), SLEEP_TIMEOUT, t);
            p.edit().remove(SLEEP_TIMEOUT).commit();
        } catch (Exception e) { Log.w(TAG, "delai de veille", e); }
    }
}
