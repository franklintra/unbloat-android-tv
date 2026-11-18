package tv.zenhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * La barre des reglages seule, par-dessus l'appli en cours (fenetre transparente, sa propre tache).
 * Ouverture : touche Reglages de la telecommande (service Keys), ou adb shell am start -n tv.zenhome/.PanelActivity
 * RETOUR au premier niveau ferme la barre et l'activite ; ouvrir une page des parametres aussi.
 * Relancee alors qu'elle est ouverte (deuxieme appui sur Reglages) : elle se ferme.
 */
public class PanelActivity extends Activity {
    FrameLayout root;
    final List<View> levels = new ArrayList<>();

    final SettingsPanel.Host host = new SettingsPanel.Host() {
        public void push(View lv, View focus) {
            root.addView(lv, new FrameLayout.LayoutParams(-1, -1));
            levels.add(lv);
            trapFocus();
            lv.setAlpha(0f); lv.animate().alpha(1f).setDuration(160).start();   // comme Home.openOverlay
            if (focus != null) focus.requestFocus();
        }
        public void pop() {
            if (!levels.isEmpty()) root.removeView(levels.remove(levels.size() - 1));
            if (levels.isEmpty()) { finish(); return; }
            trapFocus();
            levels.get(levels.size() - 1).requestFocus();
        }
        public void closeAll() {
            levels.clear();
            root.removeAllViews();
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);   // pas d'animation de fenetre : le panneau glisse tout seul
        root = new FrameLayout(this);
        setContentView(root);
        new SettingsPanel(this, root, host).show();
        if (getIntent().getBooleanExtra(EXTRA_DOCK, false)) {
            quick = new QuickDock(this);
            final View first = quick.attach(root);
            if (first != null) root.post(new Runnable() { public void run() { first.requestFocus(); } });
        }
    }

    /** Bouton TV de l'iPhone : bandeau des applis du dock sous la barre, focus dessus (QuickDock). */
    static final String EXTRA_DOCK = "dock";
    QuickDock quick;

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (levels.isEmpty()) new SettingsPanel(this, root, host).show();
        else host.closeAll();   // deuxieme appui sur Reglages : on referme
    }

    /** Seul le niveau du dessus prend le focus (meme regle que Home.trapFocus). */
    void trapFocus() {
        for (int i = 0; i < levels.size(); i++) {
            View o = levels.get(i);
            if (o instanceof ViewGroup) ((ViewGroup) o).setDescendantFocusability(
                    i == levels.size() - 1 ? ViewGroup.FOCUS_BEFORE_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (quick != null) quick.focusable(levels.size() <= 1);
    }

    // RETOUR quand aucune ligne n'a le focus (les lignes le gerent elles-memes, avec l'animation de sortie)
    @Override
    public void onBackPressed() { host.pop(); }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
