package tv.zenhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Touches Guard, Media et TCL Channel : Spotify, Apple TV et CANAL+.
 * Porte aussi le recepteur AirPlay audio (AirPlay.java), demarre avec le service.
 * Touche Reglages de la telecommande : appui court = barre des reglages par-dessus l'appli en cours,
 * appui long = appli Parametres. Service d'accessibilite qui filtre les touches avant le systeme.
 * La telecommande TCL envoie le code 4077, pas KEYCODE_SETTINGS. Les touches injectees par adb
 * ne passent pas par ce filtre : seule la vraie telecommande permet de tester.
 *
 * Le service tourne tant que la TV est allumee : il porte aussi le night shift (NightShift.apply chaque minute).
 * Il ecoute les changements de fenetre pour savoir quand on passe d'une appli a une entree TV/HDMI.
 *
 * Activation, une fois, par adb (l'ordre compte : TCL refuse de lier le service sans APP_AUTO_START) :
 *   appops set tv.zenhome APP_AUTO_START allow
 *   settings put secure enabled_accessibility_services tv.zenhome/.Keys
 *   settings put secure accessibility_enabled 1
 */
public class Keys extends AccessibilityService {

    static final int TCL_SETTINGS = 4077;

    // touches raccourcis de la telecommande TCL (codes lus dans le logcat de TclPhoneWindowManager) -> appli lancee
    static final int TCL_GUARD = 4140, TCL_MEDIA = 4057, TCL_CHANNEL = 4020;
    static String shortcut(int code) {
        switch (code) {
            case TCL_GUARD:   return "com.spotify.tv.android";
            case TCL_MEDIA:   return "com.apple.atve.androidtv.appletv";
            case TCL_CHANNEL: return "com.canal.android.canal";
            default: return null;
        }
    }
    static final long LONG_MS = 600, TICK_MS = 60000;

    static Keys self;
    final Handler h = new Handler();
    boolean longFired, onTvInput;

    // le filtre ne recoit pas les repetitions : l'appui long se detecte au minuteur, touche encore enfoncee
    final Runnable longPress = new Runnable() {
        public void run() { longFired = true; open(new Intent(Settings.ACTION_SETTINGS)); }
    };

    final Runnable tick = new Runnable() {
        public void run() { NightShift.apply(Keys.this); h.postDelayed(this, TICK_MS); }
    };

    // ecran rallume, heure ou fuseau change : on n'attend pas la minute suivante
    final BroadcastReceiver wake = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { h.removeCallbacks(tick); h.post(tick); AirPlay.wake(); }
    };

    void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    @Override
    protected void onServiceConnected() {
        self = this;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(wake, f);
        NightShift.resend(this);   // apres un redemarrage, on ne sait pas ce que la TV a garde
        h.post(tick);
        AirPlay.start(this);   // recepteur AirPlay audio, voir AirPlay.java
    }

    @Override
    public void onDestroy() {
        AirPlay.stop();
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(wake); } catch (Exception ignored) { }
        if (self == this) self = null;
        super.onDestroy();
    }

    /** Entree TV/HDMI (appli com.tcl.tv) ou applis : chacune garde son propre retroeclairage, on renvoie tout au passage. */
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        boolean tv = "com.tcl.tv".contentEquals(e.getPackageName());
        if (tv == onTvInput) return;
        onTvInput = tv;
        NightShift.resend(this);
        h.removeCallbacks(tick);
        h.postDelayed(tick, 3000);   // laisse l'entree s'ouvrir, sinon la TV reapplique ses propres valeurs apres nous
    }
    @Override public void onInterrupt() { }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        String pkg = shortcut(c);
        if (pkg != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) open(i);
            }
            return true;   // la TV ne lance plus son propre raccourci
        }
        if (c != TCL_SETTINGS && c != KeyEvent.KEYCODE_SETTINGS) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            longFired = false;
            h.postDelayed(longPress, LONG_MS);
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            h.removeCallbacks(longPress);
            if (!longFired) open(new Intent().setComponent(new ComponentName(this, "tv.zenhome.PanelActivity")));
        }
        return true;   // la TV n'ouvre plus ses propres reglages
    }
}
