package tv.zenhome;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;

/**
 * Activite invisible qui allume l'ecran puis se ferme : lancee par AirPlay quand un iPhone se connecte a la TV
 * en veille rapide. Un verrou ACQUIRE_CAUSES_WAKEUP ne suffit pas, TCL l'ignore ; le chemin "turn screen on"
 * d'une activite passe par le WindowManager, comme un reveil.
 */
public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        new Handler().postDelayed(new Runnable() { public void run() { finish(); } }, 1500);
    }
}
