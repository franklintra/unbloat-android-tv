package tv.zenhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

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
    static final long GRACE_MS = 60_000, SLEEP_TIMEOUT_MS = 5_000, ARM_MS = 20_000, SETTLE_MS = 2_000;

    static final Handler h = new Handler(Looper.getMainLooper());
    static Context ctx;
    static boolean armed;   // AirPlay vient de reveiller la TV : ecran a eteindre des qu'il s'allume
    static boolean dark;    // retroeclairage coupe par nous, personne n'a encore touche la telecommande

    static final Runnable disarm = new Runnable() { public void run() { armed = false; } };
    static final Runnable goDark = new Runnable() {
        public void run() {
            if (!dark) return;
            try {
                ctx.startService(new Intent("com.tcl.settings.SHOW_WINDOW").setPackage("com.tcl.settings").putExtra("Type", "AudioOnly"));
            } catch (Exception e) { Log.w(TAG, "mode audio seul TCL", e); }
        }
    };
    static final Runnable sleep = new Runnable() {
        public void run() {
            if (!dark || AirPlay.busy()) return;
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
        h.post(new Runnable() { public void run() {
            armed = true;
            h.removeCallbacks(disarm);
            h.postDelayed(disarm, ARM_MS);
        } });
    }

    /** Keys, ACTION_SCREEN_ON. */
    static void onScreenOn(Context c) {
        ctx = c.getApplicationContext();
        restoreTimeout(c);
        if (!armed) return;
        armed = false;
        h.removeCallbacks(disarm);
        dark = true;
        Log.i(TAG, "audio seul : l'ecran s'eteint dans 10 s, une touche le rallume");
        h.postDelayed(goDark, SETTLE_MS);   // apres la reprise TCL, qui fermerait le compte a rebours
    }

    /** Keys, ACTION_SCREEN_OFF : la TV dort, on rend la main a TCL pour le prochain reveil. */
    static void onScreenOff(Context c) {
        ctx = c.getApplicationContext();
        armed = false;
        h.removeCallbacks(goDark);
        h.removeCallbacks(sleep);
        dark = false;
        restoreTimeout(c);
    }

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

    static void sessionStarted() { h.removeCallbacks(sleep); if (ctx != null) restoreTimeout(ctx); }
    static void sessionEnded() { h.removeCallbacks(sleep); if (dark) h.postDelayed(sleep, GRACE_MS); }

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
