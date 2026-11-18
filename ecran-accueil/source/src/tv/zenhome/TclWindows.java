package tv.zenhome;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Fenetres fantomes de com.tcl.settings.
 *
 * Chaque reglage envoye a la fenetre TCL (ShowWindowService : night shift, barre des reglages, mode Audio seul)
 * laisse une fenetre TYPE_SYSTEM_ALERT cachee qui ne part jamais. Bug TCL : pour fermer, RootApp.removeDialogs
 * appelle dialog.hide() puis dialog.dismiss(), et SeekBarLayoutDialog/SelectLayoutDialog surchargent dismiss()
 * pour jouer une animation de sortie et n'appellent super.dismiss() qu'a la fin de l'animation. La vue est deja
 * GONE, l'animation ne se dessine jamais, la fenetre reste (mViewVisibility=GONE, sans surface). Toutes les
 * sorties TCL passent par la (fin du delai de 30 s, quickmenu.dismiss, changement de curseur). Le night shift
 * en laisse jusqu'a 5 par minute pendant sa rampe : ~360 en une soiree, et la veille rapide (STR) garde le
 * processus des jours.
 *
 * Seule la mort du processus retire ces fenetres. Il tient un service au premier plan (am kill refuse), donc
 * force-stop, puis un demarrage explicite de ShowWindowService (action quickmenu.dismiss : n'affiche rien) pour
 * sortir le paquet de l'etat arrete : sinon TimerManagerBroadcastReceiver (com.tcl.PowerOffTimeCount, la mise
 * en veille differee du mode Audio seul) ne recevrait plus rien. ShowMessageService et CecNotificationService
 * se relancent a la demande. Passe par AdbLocal (uid shell, FORCE_STOP_PACKAGES), toutes les CHECK_MS, TV
 * allumee, hors mode Audio seul, et seulement si aucune fenetre TCL n'est visible (menu ouvert, compte a rebours).
 */
final class TclWindows {
    static final String TAG = "ZenTcl";
    static final long CHECK_MS = 30 * 60_000;
    static final int MAX = 40;   // fenetres cachees tolerees avant de redemarrer com.tcl.settings
    static final String CMD =
            "n=$(dumpsys window windows | grep -c ' com.tcl.settings}:'); "
            + "v=$(dumpsys window visible | grep -c ' com.tcl.settings[/}]'); echo \"$n $v\"; "
            + "if [ \"$n\" -gt " + MAX + " ] && [ \"$v\" = 0 ]; then am force-stop com.tcl.settings "
            + "&& am startservice -n com.tcl.settings/.ShowWindowService -a com.tcl.quickmenu.dismiss >/dev/null "
            + "&& echo nettoye; fi";

    static long last = -CHECK_MS;
    // mode Audio seul lance par la barre des reglages (pas par AirPlay, voir AudioOnly) : la mise en veille differee
    // est une alarme de com.tcl.settings, qu'un force-stop effacerait. Fin a la prochaine touche ou au changement d'ecran.
    static volatile boolean audioOnly;
    static volatile boolean running;

    private TclWindows() { }

    /** Keys, tic de chaque minute (thread principal) : AdbLocal bloque, on part sur un thread. */
    static void tick(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (running || now - last < CHECK_MS) return;
        // force-stop pendant qu'un reglage night shift attend dans la file TCL : il est perdu (vu le 2026-11-18,
        // retroeclairage reste a 1 le matin). On attend un tic sans envoi.
        if (now - NightShift.lastSent < 30_000) return;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive() || AudioOnly.dark || AudioOnly.armed || audioOnly) return;
        last = now;
        running = true;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() { public void run() {
            try {
                if (AudioOnly.dark || AudioOnly.armed || audioOnly) return;
                String r = AdbLocal.shell(app, CMD, 20_000);
                if (r == null) Log.w(TAG, "adb local indisponible, fenetres TCL non nettoyees");
                else if (r.contains("nettoye")) {
                    Log.i(TAG, "com.tcl.settings redemarre, fenetres cachees retirees : " + r.trim());
                    NightShift.resend(app);   // un envoi peut s'etre perdu malgre tout : le prochain tic renvoie tout
                }
            } finally { running = false; }
        } }, "ZenTcl").start();
    }
}
