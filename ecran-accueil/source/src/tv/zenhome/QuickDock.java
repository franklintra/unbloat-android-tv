package tv.zenhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.InputStream;

/**
 * Bandeau discret des applis du dock, en bas a gauche de la barre des reglages, pour changer d'appli sans
 * repasser par l'accueil (bouton TV de l'iPhone, clic court). Memes applis et memes bannieres que le dock de
 * Home (prefs "zenhome" / "dock", sinon settings global zenhome_dock). OK lance l'appli et ferme tout.
 */
final class QuickDock {
    static final float TILE_W = 84, TILE_H = 47, GAP = 10, PAD = 12;   // unites sur 1280

    final Activity act;
    final float density;
    LinearLayout bar;

    QuickDock(Activity act) {
        this.act = act;
        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        density = Math.max(dm.widthPixels, dm.heightPixels) / 1280f;
    }

    int dp(float v) { return Math.round(v * density); }

    String[] packages() {
        String csv = act.getSharedPreferences("zenhome", Activity.MODE_PRIVATE).getString("dock", "");
        if (csv == null || csv.isEmpty()) {
            try { csv = Settings.Global.getString(act.getContentResolver(), "zenhome_dock"); } catch (Exception ignored) { }
        }
        return csv == null || csv.isEmpty() ? new String[0] : csv.split("[,;\\s]+");
    }

    /** Ajoute le bandeau dans root ; renvoie la premiere tuile (focus initial), ou null sans appli. */
    View attach(FrameLayout root) {
        bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false); bar.setClipToPadding(false);
        bar.setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD));
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(dp(18));
        glass.setColor(0xB31E2127);
        glass.setStroke(Math.max(1, dp(0.75f)), 0x33FFFFFF);
        bar.setBackground(glass);
        View first = null;
        for (String p : packages()) {
            View t = tile(p.trim());
            if (t == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H));
            if (first != null) lp.leftMargin = dp(GAP);
            bar.addView(t, lp);
            if (first == null) first = t;
        }
        if (first == null) return null;
        // zone a gauche de la barre des reglages (PW + ombre), centree, en bas
        FrameLayout outer = new FrameLayout(act);
        outer.setClipChildren(false); outer.setClipToPadding(false);
        FrameLayout.LayoutParams lpB = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpB.bottomMargin = dp(36);
        outer.addView(bar, lpB);
        FrameLayout.LayoutParams lpO = new FrameLayout.LayoutParams(-1, -1);
        lpO.rightMargin = dp(SettingsPanel.PW + 32);
        root.addView(outer, 0, lpO);
        bar.setAlpha(0f); bar.setTranslationY(dp(16));
        bar.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(new DecelerateInterpolator(2f)).start();
        return first;
    }

    View tile(final String pkg) {
        if (pkg.isEmpty()) return null;
        PackageManager pm = act.getPackageManager();
        Drawable d;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (!ai.enabled) return null;
            d = banner(pkg);
            if (d == null) d = pm.getApplicationBanner(ai);
            if (d == null) { Intent li = pm.getLeanbackLaunchIntentForPackage(pkg); if (li != null) d = pm.getActivityBanner(li); }
            if (d == null) d = pm.getApplicationIcon(ai);
        } catch (Exception e) { return null; }
        final Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg) != null ? pm.getLeanbackLaunchIntentForPackage(pkg) : pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return null;
        final FrameLayout f = new FrameLayout(act);
        final GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(9)); bg.setColor(0xFF2A2D33);
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(9)); } });
        ImageView iv = new ImageView(act);
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        f.setAlpha(0.72f);
        f.setFocusable(true); f.setClickable(true);
        f.setOnFocusChangeListener(new View.OnFocusChangeListener() { public void onFocusChange(View v, boolean has) {
            v.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f).alpha(has ? 1f : 0.72f)
                    .translationZ(has ? dp(8) : 0).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            GradientDrawable ring = null;
            if (has) { ring = new GradientDrawable(); ring.setCornerRadius(dp(9)); ring.setStroke(dp(2), 0xF2FFFFFF); }
            v.setForeground(ring);
        } });
        f.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { act.startActivity(launch); } catch (Exception ignored) { }
            act.finish();
        } });
        return f;
    }

    Drawable banner(String pkg) {
        try {
            InputStream is = act.getAssets().open("banners/" + pkg + ".png");
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            return b == null ? null : new BitmapDrawable(act.getResources(), b);
        } catch (Exception e) { return null; }
    }

    /** Sous-menu des reglages ouvert : le bandeau ne prend plus le focus. */
    void focusable(boolean on) {
        if (bar != null) bar.setDescendantFocusability(on ? android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                : android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }
}
