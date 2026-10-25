package tv.zenhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Touche Reglages de la telecommande : appui court = barre des reglages par-dessus l'appli en cours,
 * appui long = appli Parametres. Service d'accessibilite qui filtre les touches avant le systeme.
 * La telecommande TCL envoie le code 4077, pas KEYCODE_SETTINGS. Les touches injectees par adb
 * ne passent pas par ce filtre : seule la vraie telecommande permet de tester.
 *
 * Le service tourne tant que la TV est allumee : il porte aussi le night shift (NightShift.apply chaque minute)
 * et son voile noir, une fenetre d'accessibilite qui passe au-dessus de toutes les applis, video comprise.
 * Il ecoute les changements de fenetre pour savoir quand on passe d'une appli a une entree TV/HDMI.
 *
 * Activation, une fois, par adb (l'ordre compte : TCL refuse de lier le service sans APP_AUTO_START) :
 *   appops set tv.zenhome APP_AUTO_START allow
 *   settings put secure enabled_accessibility_services tv.zenhome/.Keys
 *   settings put secure accessibility_enabled 1
 */
public class Keys extends AccessibilityService {

    static final int TCL_SETTINGS = 4077;
    static final long LONG_MS = 600, TICK_MS = 60000;

    static Keys self;
    final Handler h = new Handler();
    boolean longFired, onTvInput;
    View veil;

    // le filtre ne recoit pas les repetitions : l'appui long se detecte au minuteur, touche encore enfoncee
    final Runnable longPress = new Runnable() {
        public void run() { longFired = true; open(new Intent(Settings.ACTION_SETTINGS)); }
    };

    final Runnable tick = new Runnable() {
        public void run() { NightShift.apply(Keys.this); h.postDelayed(this, TICK_MS); }
    };

    // ecran rallume, heure ou fuseau change : on n'attend pas la minute suivante
    final BroadcastReceiver wake = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { h.removeCallbacks(tick); h.post(tick); }
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
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(wake); } catch (Exception ignored) { }
        setVeil(0);
        if (self == this) self = null;
        super.onDestroy();
    }

    /** Opacite du voile noir, 0 a 100 ; sans effet si le service ne tourne pas. */
    static void veil(int pct) { if (self != null) self.setVeil(pct); }

    void setVeil(int pct) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (pct <= 0 || Build.VERSION.SDK_INT < 22) {
            if (veil != null) wm.removeView(veil);
            veil = null;
            return;
        }
        if (veil == null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("NightShiftVeil");
            veil = new View(this);
            wm.addView(veil, lp);
        }
        veil.setBackgroundColor(Color.argb(Math.round(255 * Math.min(100, pct) / 100f), 0, 0, 0));
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
