package tv.zenhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Touche Reglages de la telecommande : appui court = barre des reglages par-dessus l'appli en cours,
 * appui long = appli Parametres. Service d'accessibilite qui filtre les touches avant le systeme.
 * La telecommande TCL envoie le code 4077, pas KEYCODE_SETTINGS. Les touches injectees par adb
 * ne passent pas par ce filtre : seule la vraie telecommande permet de tester.
 *
 * Activation, une fois, par adb (l'ordre compte : TCL refuse de lier le service sans APP_AUTO_START) :
 *   appops set tv.zenhome APP_AUTO_START allow
 *   settings put secure enabled_accessibility_services tv.zenhome/.Keys
 *   settings put secure accessibility_enabled 1
 */
public class Keys extends AccessibilityService {

    static final int TCL_SETTINGS = 4077;
    static final long LONG_MS = 600;

    final Handler h = new Handler();
    boolean longFired;

    // le filtre ne recoit pas les repetitions : l'appui long se detecte au minuteur, touche encore enfoncee
    final Runnable longPress = new Runnable() {
        public void run() { longFired = true; open(new Intent(Settings.ACTION_SETTINGS)); }
    };

    void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
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
