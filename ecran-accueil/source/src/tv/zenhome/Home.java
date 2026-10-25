package tv.zenhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Zen Home : ecran d'accueil minimaliste pour Android TV, facon Apple TV.
 * Photo plein ecran qui change + horloge + dock d'applis personnalisable.
 *
 * Personnalisation directement sur la TV : appui long sur OK (ou touche Menu) sur une appli du dock
 *   -> deplacer, remplacer, retirer, ajouter, disposition 4/6/8 applis, titre.
 *
 * Reglages envoyes par le kit (adb), pris en compte quand zenhome_rev change :
 *   settings put global zenhome_rev         <horodatage>
 *   settings put global zenhome_title_mode  device | none | custom
 *   settings put global zenhome_title_b64   <titre en base64 UTF-8>   (si custom)
 *   settings put global zenhome_layout      4 | 6 | 8
 *   settings put global zenhome_dock        com.netflix.ninja,com.google.android.youtube.tv,...
 *   settings put global zenhome_interval    60   (secondes entre deux photos)
 * Photos perso : fichiers .jpg/.png dans /sdcard/ZenHome/ (remplacent les photos fournies).
 */
public class Home extends Activity {

    /** Applis de streaming connues, par ordre de priorite pour le dock automatique. */
    static final String[] KNOWN_DOCK = {
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.canal.android.canal", "com.wbd.stream",
            "com.apple.atve.androidtv.appletv", "com.spotify.tv.android", "com.plexapp.android",
            "fr.francetv.pluzz", "fr.tf1.mytf1", "fr.m6.m6replay", "tv.arte.plus7", "tv.molotov.app",
            "com.cbs.ott", "com.dazn", "tv.twitch.android.app", "org.xbmc.kodi", "com.google.android.youtube.tvkids",
    };
    /** Applis "entrees TV / chaines" selon les marques. */
    static final String[][] KNOWN_INPUTS = {
            {"com.tcl.tv", "com.tcl.tv.TVActivity"},
            {"com.mediatek.wwtv.tvcenter", null},
            {"org.droidtv.playtv", null},
            {"com.sony.dtv.tvx", null},
            {"com.mitv.tvplayer", null},
            {"com.mitv.livetv", null},
            {"com.hisense.tv", null},
            {"com.google.android.tv", null},
            {"com.android.tv", null},
    };
    static final long FADE_MS = 2500L;
    static final int ACCENT = 0xFF7CC4FF;

    final Handler h = new Handler();
    SharedPreferences prefs;
    long rotationMs = 60_000L;
    FrameLayout root, wallLayer;
    ImageView wallA, wallB;
    boolean aOnTop = true, released = false;
    TextView clock, date, titleView, hintView;
    List<String> walls = new ArrayList<>();
    boolean wallsFromFiles = false;
    int wallIdx = 0;
    LinearLayout dock;
    final List<View> overlays = new ArrayList<>();   // menus / listes ouverts (pile)
    Typeface fReg, fMed, fLight, fIcons;
    float density;
    boolean fr;

    // configuration du dock
    List<String> dockPkgs = new ArrayList<>();
    int layout = 4;               // 4, 6 ou 8 applis
    String titleMode = "device";  // device | none | custom
    String titleText = "";
    int moveIndex = -1;           // >= 0 : appli en cours de deplacement
    List<View> bigTiles = new ArrayList<>();

    int dp(float v) { return Math.round(v * density); }
    String t(String frTxt, String enTxt) { return fr ? frTxt : enTxt; }

    String setting(String key) {
        try { return Settings.Global.getString(getContentResolver(), key); } catch (Throwable e) { return null; }
    }

    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        fr = "fr".equals(Locale.getDefault().getLanguage());
        prefs = getSharedPreferences("zenhome", MODE_PRIVATE);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        density = Math.max(dm.widthPixels, dm.heightPixels) / 1280f; // maquette sur 1280 unites de large
        try {
            fReg = Typeface.createFromAsset(getAssets(), "Poppins-Regular.ttf");
            fMed = Typeface.createFromAsset(getAssets(), "Poppins-Medium.ttf");
            fLight = Typeface.createFromAsset(getAssets(), "Poppins-Light.ttf");
        } catch (Exception e) { fReg = fMed = fLight = Typeface.SANS_SERIF; }
        try { fIcons = Typeface.createFromAsset(getAssets(), "MaterialIconsRound-Regular.otf"); } catch (Exception e) { fIcons = null; }
        try {
            String iv = setting("zenhome_interval");
            if (iv != null) rotationMs = Math.max(10, Integer.parseInt(iv.trim())) * 1000L;
        } catch (Exception ignored) { }
        loadConfig();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        wallA = newWall(); wallB = newWall();
        wallLayer = new FrameLayout(this);
        wallLayer.addView(wallB); wallLayer.addView(wallA);
        root.addView(wallLayer, new FrameLayout.LayoutParams(-1, -1));

        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(200), Gravity.TOP));
        View bot = new View(this);
        bot.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000}));
        root.addView(bot, new FrameLayout.LayoutParams(-1, dp(340), Gravity.BOTTOM));

        // Titre
        titleView = text("", 26, fMed, 0xFFFFFFFF);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setMaxWidth(dp(820));
        FrameLayout.LayoutParams lpT = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lpT.setMargins(dp(56), dp(38), 0, 0);
        root.addView(titleView, lpT);
        applyTitle();

        // Horloge + date
        LinearLayout cl = new LinearLayout(this);
        cl.setOrientation(LinearLayout.VERTICAL);
        cl.setGravity(Gravity.END);
        clock = text("", 40, fLight, 0xFFFFFFFF);
        date = text("", 15, fReg, 0xE6FFFFFF);
        cl.addView(clock); cl.addView(date);
        FrameLayout.LayoutParams lpC = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lpC.setMargins(0, dp(26), dp(56), 0);
        root.addView(cl, lpC);

        // Indication pendant le deplacement
        hintView = text("", 13, fMed, 0xFFFFFFFF);
        GradientDrawable hb = new GradientDrawable(); hb.setCornerRadius(dp(16)); hb.setColor(0xB3101216);
        hintView.setBackground(hb);
        hintView.setPadding(dp(16), dp(8), dp(16), dp(8));
        hintView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lpH = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpH.setMargins(0, 0, 0, dp(layoutHint()));
        root.addView(hintView, lpH);

        // Dock
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setClipChildren(false); dock.setClipToPadding(false);
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(dp(28));
        glass.setColor(0x38FFFFFF);
        glass.setStroke(Math.max(1, dp(0.75f)), 0x40FFFFFF);
        dock.setBackground(glass);
        FrameLayout.LayoutParams lpD = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpD.setMargins(0, 0, 0, dp(46));
        root.addView(dock, lpD);
        buildDock(0);

        setContentView(root);
        loadWalls();
        showWall(true);
    }

    // =====================================================================
    // Configuration : preferences de la TV, remplacees par celles du kit si plus recentes
    void loadConfig() {
        String rev = setting("zenhome_rev");
        if (rev != null && !rev.equals(prefs.getString("rev", null))) {
            SharedPreferences.Editor e = prefs.edit().putString("rev", rev);
            String mode = setting("zenhome_title_mode");
            if (mode != null) e.putString("title_mode", mode.trim());
            String b64 = setting("zenhome_title_b64");
            if (b64 != null && b64.trim().length() > 0) {
                try { e.putString("title", new String(Base64.decode(b64.trim(), Base64.DEFAULT), "UTF-8").trim()); } catch (Exception ignored) { }
            }
            String lay = setting("zenhome_layout");
            if (lay != null) e.putString("layout", lay.trim());
            String d = setting("zenhome_dock");
            if (d != null) {
                // "auto" : le kit demande le choix automatique des applis
                if ("auto".equalsIgnoreCase(d.trim())) e.putString("dock", "").putBoolean("dock_user_set", false);
                else e.putString("dock", d.trim()).putBoolean("dock_user_set", true);
            }
            e.apply();
        }
        titleMode = prefs.getString("title_mode", "device");
        titleText = prefs.getString("title", "");
        try { layout = Integer.parseInt(prefs.getString("layout", "4")); } catch (Exception ex) { layout = 4; }
        if (layout != 6 && layout != 8) layout = 4;
        dockPkgs.clear();
        String csv = prefs.getString("dock", "");
        if (csv.length() > 0) {
            for (String p : csv.split("[,;\\s]+")) { p = p.trim(); if (p.length() > 0 && usable(p) && !dockPkgs.contains(p)) dockPkgs.add(p); }
        }
        if (dockPkgs.isEmpty() && !prefs.getBoolean("dock_user_set", false)) {
            for (String p : KNOWN_DOCK) { if (dockPkgs.size() >= layout) break; if (usable(p)) dockPkgs.add(p); }
        }
        while (dockPkgs.size() > layout) dockPkgs.remove(dockPkgs.size() - 1);
    }

    void saveConfig() {
        prefs.edit()
                .putString("dock", TextUtils.join(",", dockPkgs))
                .putBoolean("dock_user_set", true)
                .putString("layout", String.valueOf(layout))
                .putString("title_mode", titleMode)
                .putString("title", titleText)
                .apply();
    }

    String deviceName() {
        String n = setting("device_name");
        if (n == null || n.trim().isEmpty() || "null".equals(n)) n = Build.MODEL;
        return n == null ? "" : n.trim();
    }

    void applyTitle() {
        String s = "custom".equals(titleMode) ? titleText : ("none".equals(titleMode) ? "" : deviceName());
        titleView.setText(s);
        titleView.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    boolean usable(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled && launchIntent(pkg) != null;
        } catch (Exception e) { return false; }
    }

    // =====================================================================
    // Dock : grandes tuiles (1 ou 2 rangees) + petites tuiles (Play Store, Entrees, Toutes, Reglages)
    int bigW() { return layout == 4 ? 176 : 128; }
    int bigH() { return layout == 4 ? 99 : 72; }
    int bigM() { return layout == 4 ? 9 : 7; }
    int smallW() { return layout == 6 ? 64 : 86; }
    int smallH() { return layout == 6 ? 60 : (layout == 8 ? 72 : 80); }
    int layoutHint() { return layout == 8 ? 266 : (layout == 6 ? 174 : 200); }

    /** Appui long propre a une tuile du dock (ex. Reglages) ; sans entree, le menu d'edition du dock. */
    final java.util.Map<View, Runnable> longActions = new java.util.HashMap<>();

    void longPress(View f) {
        Runnable r = longActions.get(f);
        if (r != null) r.run(); else showTileMenu(bigTiles.indexOf(f));
    }

    void buildDock(int focusIndex) {
        dock.removeAllViews();
        longActions.clear();
        bigTiles.clear();
        int rows = layout == 8 ? 2 : 1;
        int cols = layout == 8 ? 4 : layout;
        dock.setPadding(dp(layout == 4 ? 22 : 18), dp(layout == 8 ? 16 : 18), dp(layout == 4 ? 22 : 18), dp(layout == 8 ? 16 : 18));

        GridLayout big = new GridLayout(this);
        big.setColumnCount(Math.max(1, Math.min(cols, dockPkgs.size() + (dockPkgs.size() < layout ? 1 : 0))));
        big.setClipChildren(false); big.setClipToPadding(false);
        for (int i = 0; i < dockPkgs.size() && i < layout; i++) {
            View v = bigTile(dockPkgs.get(i), i);
            if (v != null) { big.addView(v); bigTiles.add(v); }
        }
        if (dockPkgs.size() < layout) big.addView(addTile());   // case "+" pour ajouter une appli
        dock.addView(big);

        View sep = new View(this); sep.setBackgroundColor(0x4DFFFFFF);
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(dp(1.5f), dp(rows == 2 ? 140 : (layout == 6 ? 52 : 64)));
        lpS.setMargins(dp(layout == 6 ? 10 : 14), 0, dp(layout == 6 ? 10 : 14), 0);
        dock.addView(sep, lpS);

        GridLayout small = new GridLayout(this);
        small.setColumnCount(rows == 2 ? 2 : 4);
        small.setClipChildren(false); small.setClipToPadding(false);
        if (usable("com.android.vending"))
            small.addView(smallTile(pkgIcon("com.android.vending"), "Play Store", new Runnable() { public void run() { launch("com.android.vending"); } }));
        final String[] input = findInput();
        if (input != null)
            small.addView(smallTile(pkgIcon(input[0]), t("Entrées TV", "TV inputs"), new Runnable() { public void run() { openInput(input); } }));
        small.addView(smallTile(new GridIcon(), t("Toutes", "All apps"), new Runnable() { public void run() { showApps(null); } }));
        Drawable gear = pkgIcon("com.android.tv.settings");
        if (gear == null) gear = pkgIcon("com.android.settings");
        View settingsBtn = smallTile(gear, t("Réglages", "Settings"), new Runnable() { public void run() { showSettingsPanel(); } });
        // appui long : l'appli Parametres directement, sans passer par la barre laterale
        longActions.put(settingsBtn, new Runnable() { public void run() {
            startSafe(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } });
        small.addView(settingsBtn);
        dock.addView(small);

        // repositionne l'indication au-dessus du dock
        FrameLayout.LayoutParams lpH = (FrameLayout.LayoutParams) hintView.getLayoutParams();
        if (lpH != null) { lpH.bottomMargin = dp(layoutHint()); hintView.setLayoutParams(lpH); }

        final View target = (focusIndex >= 0 && focusIndex < bigTiles.size()) ? bigTiles.get(focusIndex)
                : (bigTiles.isEmpty() ? (big.getChildCount() > 0 ? big.getChildAt(0) : small.getChildAt(0)) : bigTiles.get(bigTiles.size() - 1));
        if (target != null) dock.post(new Runnable() { public void run() { target.requestFocus(); } });
    }

    View bigTile(final String pkg, final int index) {
        PackageManager pm = getPackageManager();
        Drawable d = null;
        String label = pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(ai).toString();
            d = customBanner(pkg);
            if (d == null) d = pm.getApplicationBanner(ai);
            if (d == null) { Intent li = pm.getLeanbackLaunchIntentForPackage(pkg); if (li != null) d = pm.getActivityBanner(li); }
        } catch (Exception e) { return null; }
        FrameLayout f = roundedFrame(14, 0xFF1E1E1E);
        if (d != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(d);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this); iv.setImageDrawable(pkgIcon(pkg));
            int is = layout == 4 ? 44 : 32;
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(is), dp(is)); il.gravity = Gravity.CENTER_HORIZONTAL;
            l.addView(iv, il);
            TextView tv = text(label, layout == 4 ? 11 : 9, fMed, 0xFFFFFFFF); tv.setGravity(Gravity.CENTER); tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(6), dp(4), dp(6), 0);
            l.addView(tv, new LinearLayout.LayoutParams(-1, -2));
            f.addView(l, new FrameLayout.LayoutParams(-1, -1));
        }
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(bigW()); lp.height = dp(bigH());
        lp.setMargins(dp(bigM()), dp(layout == 8 ? 6 : 0), dp(bigM()), dp(layout == 8 ? 6 : 0));
        f.setLayoutParams(lp);
        f.setContentDescription(label);
        f.setTag(14);
        wire(f, new Runnable() { public void run() { launch(pkg); } }, layout == 4 ? 1.14f : 1.12f);
        f.setLongClickable(true);
        f.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showTileMenu(index); return true; } });
        return f;
    }

    View addTile() {
        FrameLayout f = roundedFrame(14, 0x1FFFFFFF);
        GradientDrawable bg = (GradientDrawable) f.getBackground();
        bg.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
        TextView plus = text("+", layout == 4 ? 34 : 26, fLight, 0xCCFFFFFF);
        plus.setGravity(Gravity.CENTER); plus.setShadowLayer(0, 0, 0, 0);
        f.addView(plus, new FrameLayout.LayoutParams(-1, -1));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(bigW()); lp.height = dp(bigH());
        lp.setMargins(dp(bigM()), dp(layout == 8 ? 6 : 0), dp(bigM()), dp(layout == 8 ? 6 : 0));
        f.setLayoutParams(lp);
        f.setContentDescription(t("Ajouter une appli", "Add an app"));
        f.setTag(14);
        wire(f, new Runnable() { public void run() { pickAppToAdd(); } }, 1.1f);
        f.setLongClickable(true);
        f.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { showTileMenu(-1); return true; } });
        return f;
    }

    View smallTile(Drawable icon, String label, Runnable r) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(layout == 6 ? 14 : 18)); bg.setColor(0x33FFFFFF);
        l.setBackground(bg);
        if (icon == null) icon = new GridIcon();
        ImageView iv = new ImageView(this); iv.setImageDrawable(icon);
        int is = layout == 6 ? 24 : (layout == 8 ? 30 : 34);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(is), dp(is));
        il.gravity = Gravity.CENTER_HORIZONTAL;
        l.addView(iv, il);
        TextView tv = text(label, layout == 6 ? 8 : 10, fMed, 0xFFFFFFFF); tv.setShadowLayer(0, 0, 0, 0);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setIncludeFontPadding(true);
        tv.setPadding(dp(2), dp(layout == 6 ? 2 : 4), dp(2), 0);
        l.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(smallW()); lp.height = dp(smallH());
        int m = layout == 6 ? 5 : 7;
        lp.setMargins(dp(m), dp(layout == 8 ? 6 : 0), dp(m), dp(layout == 8 ? 6 : 0));
        l.setLayoutParams(lp);
        l.setTag(layout == 6 ? 14 : 18);
        l.setContentDescription(label);
        wire(l, r, 1.12f);
        l.setLongClickable(true);
        l.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { longPress(v); return true; } });
        return l;
    }

    FrameLayout roundedFrame(final int radius, int color) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(radius)); bg.setColor(color);
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(radius)); } });
        return f;
    }

    void wire(final View v, final Runnable r, final float scale) { wire(v, r, scale, null); }

    /** label : vue affichee seulement quand v a le focus (nom sous une tuile). */
    void wire(final View v, final Runnable r, final float scale, final View label) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) {
            if (moveIndex >= 0) return;
            if (isInDock(x)) {
                // dock : on attend un peu, une rafale de OK (telecommandes Sony) vaut un appui long
                if (pendingClick != null) h.removeCallbacks(pendingClick);
                pendingClick = r;
                h.postDelayed(r, BURST_GAP_MS);
            } else r.run();
        } });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View x, boolean has) {
                x.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                        .translationY(has ? -dp(6) : 0).translationZ(has ? dp(12) : 0)
                        .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
                int r = x.getTag() instanceof Integer ? (Integer) x.getTag() : 14;
                x.setForeground(has ? ring(r, moveIndex >= 0 ? ACCENT : 0xF2FFFFFF) : null);
                if (label != null) label.animate().alpha(has ? 1f : 0f).setDuration(180).start();
            }
        });
    }

    GradientDrawable ring(int r, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(r)); g.setColor(0x00000000); g.setStroke(dp(moveIndex >= 0 ? 3 : 2), color);
        return g;
    }

    Drawable pkgIcon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { return null; }
    }

    /** Banniere de remplacement fournie dans assets/banners/<paquet>.png (ex. logo YouTube pour SmartTube). */
    Drawable customBanner(String pkg) {
        try {
            InputStream is = getAssets().open("banners/" + pkg + ".png");
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            return b == null ? null : new BitmapDrawable(getResources(), b);
        } catch (Exception e) { return null; }
    }

    String appLabel(String pkg) {
        try { PackageManager pm = getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    // =====================================================================
    // Mode edition (appui long sur OK, ou touche Menu)
    void showTileMenu(final int index) {
        if (moveIndex >= 0 || !overlays.isEmpty()) return;
        List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        String header;
        if (index >= 0 && index < dockPkgs.size()) {
            header = appLabel(dockPkgs.get(index));
            if (dockPkgs.size() > 1) { labels.add(t("Déplacer", "Move")); actions.add(new Runnable() { public void run() { startMove(index); } }); }
            labels.add(t("Remplacer par une autre appli", "Replace with another app")); actions.add(new Runnable() { public void run() { pickAppToReplace(index); } });
            labels.add(t("Retirer du dock", "Remove from dock")); actions.add(new Runnable() { public void run() {
                dockPkgs.remove(index); saveConfig(); buildDock(Math.max(0, index - 1)); } });
        } else {
            header = t("Écran d'accueil", "Home screen");
        }
        if (dockPkgs.size() < layout) { labels.add(t("Ajouter une appli", "Add an app")); actions.add(new Runnable() { public void run() { pickAppToAdd(); } }); }
        labels.add(t("Disposition : ", "Layout: ") + layout + t(" applis", " apps")); actions.add(new Runnable() { public void run() { showLayoutMenu(); } });
        labels.add(t("Modifier le titre", "Change the title")); actions.add(new Runnable() { public void run() { editTitle(); } });
        showMenu(header, labels, actions);
    }

    void showLayoutMenu() {
        final int[] opts = {4, 6, 8};
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String[] desc = {t("4 applis, grandes tuiles", "4 apps, large tiles"), t("6 applis sur une ligne", "6 apps in one row"), t("8 applis sur deux lignes", "8 apps in two rows")};
        for (int i = 0; i < opts.length; i++) {
            final int n = opts[i];
            labels.add((n == layout ? "✓  " : "     ") + desc[i]);
            actions.add(new Runnable() { public void run() {
                layout = n;
                while (dockPkgs.size() > layout) dockPkgs.remove(dockPkgs.size() - 1);
                if (dockPkgs.size() < layout && !prefs.getBoolean("dock_user_set", false)) {
                    for (String p : KNOWN_DOCK) { if (dockPkgs.size() >= layout) break; if (usable(p) && !dockPkgs.contains(p)) dockPkgs.add(p); }
                }
                saveConfig(); buildDock(0); } });
        }
        showMenu(t("Disposition", "Layout"), labels, actions);
    }

    void editTitle() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setText("custom".equals(titleMode) ? titleText : "");
        et.setHint(deviceName());
        et.setSelection(et.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(et);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(t("Titre de l'écran d'accueil", "Home screen title"))
                .setMessage(t("Laissez vide pour afficher le nom de la TV.", "Leave empty to show the TV's name."))
                .setView(box)
                .setPositiveButton(t("Valider", "Save"), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String s = et.getText().toString().trim();
                        if (s.isEmpty()) { titleMode = "device"; titleText = ""; } else { titleMode = "custom"; titleText = s; }
                        saveConfig(); applyTitle(); } })
                .setNeutralButton(t("Aucun titre", "No title"), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { titleMode = "none"; saveConfig(); applyTitle(); } })
                .setNegativeButton(t("Annuler", "Cancel"), null)
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    public void onDismiss(android.content.DialogInterface d) { focusDock(); } })
                .show();
    }

    void startMove(int index) {
        moveIndex = index;
        hintView.setText(t("◀  ▶  pour déplacer   ·   OK pour valider", "◀  ▶  to move   ·   OK to confirm"));
        hintView.setVisibility(View.VISIBLE);
        buildDock(index);
    }

    void endMove() {
        moveIndex = -1;
        hintView.setVisibility(View.GONE);
        saveConfig();
        int keep = 0;
        View f = getCurrentFocus();
        if (f != null && bigTiles.contains(f)) keep = bigTiles.indexOf(f);
        buildDock(keep);
    }

    boolean isInDock(View v) {
        while (v != null) { if (v == dock) return true; v = v.getParent() instanceof View ? (View) v.getParent() : null; }
        return false;
    }

    boolean swallowOkUp = false;

    // Certaines telecommandes (Sony a infrarouge) envoient un appui maintenu comme une rafale
    // d'appuis courts : BURST_PRESSES appuis espaces de moins de BURST_GAP_MS = appui long.
    static final long BURST_GAP_MS = 250;
    static final int BURST_PRESSES = 3;
    Runnable pendingClick;            // clic du dock en attente (annule si une rafale commence)
    View burstView;
    int okBurst = 0;
    long lastOkUp = 0, lastOkEvent = 0;
    boolean burstSwallow = false;     // rafale deja transformee en appui long : on avale la suite

    static boolean isOk(int c) {
        return c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        // appui long sur OK, aussi pour les telecommandes qui envoient la touche en repetition
        if (isOk(kc) && moveIndex < 0) {
            long t = e.getEventTime();
            if (burstSwallow) {
                if (t - lastOkEvent < BURST_GAP_MS) { lastOkEvent = t; return true; }
                burstSwallow = false;
            }
            lastOkEvent = t;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && overlays.isEmpty()) {
                View f = getCurrentFocus();
                if (f != null && isInDock(f)) {
                    okBurst = (f == burstView && t - lastOkUp < BURST_GAP_MS) ? okBurst + 1 : 1;
                    burstView = f;
                    if (okBurst >= 2 && pendingClick != null) { h.removeCallbacks(pendingClick); pendingClick = null; }
                    if (okBurst >= BURST_PRESSES) {
                        okBurst = 0;
                        burstSwallow = true;
                        f.cancelLongPress(); f.setPressed(false);
                        longPress(f);
                        return true;
                    }
                }
            }
            if (e.getAction() == KeyEvent.ACTION_UP) lastOkUp = t;
            if (e.getAction() == KeyEvent.ACTION_UP && swallowOkUp) { swallowOkUp = false; return true; }
            if (e.getAction() == KeyEvent.ACTION_DOWN && overlays.isEmpty() && (e.getRepeatCount() >= 1 || e.isLongPress())) {
                View f = getCurrentFocus();
                if (f != null && isInDock(f)) {
                    swallowOkUp = true;
                    f.cancelLongPress(); f.setPressed(false);
                    longPress(f);
                    return true;
                }
            }
            if (swallowOkUp && e.getAction() == KeyEvent.ACTION_DOWN) return true;
        }
        if (moveIndex >= 0 && e.getAction() == KeyEvent.ACTION_DOWN) {
            int c = e.getKeyCode();
            int target = moveIndex;
            int cols = layout == 8 ? 4 : layout;
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) target = moveIndex - 1;
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) target = moveIndex + 1;
            else if (c == KeyEvent.KEYCODE_DPAD_UP && layout == 8) target = moveIndex - cols;
            else if (c == KeyEvent.KEYCODE_DPAD_DOWN && layout == 8) target = moveIndex + cols;
            else if (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_BACK
                    || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A) {
                endMove(); return true;
            } else return true;
            if (target >= 0 && target < dockPkgs.size() && target != moveIndex) {
                Collections.swap(dockPkgs, moveIndex, target);
                moveIndex = target;
                buildDock(target);
            }
            return true;
        }
        if (moveIndex >= 0) return true; // ignore les relachements pendant le deplacement
        return super.dispatchKeyEvent(e);
    }

    void pickAppToAdd() {
        showApps(new Picker() { public void picked(String pkg) {
            dockPkgs.remove(pkg);
            if (dockPkgs.size() >= layout) return;
            dockPkgs.add(pkg); saveConfig(); buildDock(dockPkgs.indexOf(pkg)); } });
    }

    void pickAppToReplace(final int index) {
        showApps(new Picker() { public void picked(String pkg) {
            if (index >= dockPkgs.size()) return;
            int existing = dockPkgs.indexOf(pkg);
            if (existing >= 0 && existing != index) Collections.swap(dockPkgs, existing, index);
            else dockPkgs.set(index, pkg);
            saveConfig(); buildDock(index); } });
    }

    // ---------- Menus en surimpression ----------
    void showMenu(String header, List<String> labels, final List<Runnable> actions) {
        final FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0x99000000);
        ov.setClickable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pb = new GradientDrawable(); pb.setCornerRadius(dp(22)); pb.setColor(0xF21C1E24);
        pb.setStroke(Math.max(1, dp(0.75f)), 0x33FFFFFF);
        panel.setBackground(pb);
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        panel.setClipChildren(false); panel.setClipToPadding(false);
        TextView hd = text(header, 16, fMed, 0xFFFFFFFF); hd.setShadowLayer(0, 0, 0, 0);
        hd.setPadding(dp(12), 0, dp(12), dp(10)); hd.setSingleLine(true); hd.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(hd);
        View first = null;
        for (int i = 0; i < labels.size(); i++) {
            final Runnable a = actions.get(i);
            TextView row = text(labels.get(i), 13, fReg, 0xFFFFFFFF); row.setShadowLayer(0, 0, 0, 0);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setSingleLine(true);
            final GradientDrawable rb = new GradientDrawable(); rb.setCornerRadius(dp(12)); rb.setColor(0x00FFFFFF);
            row.setBackground(rb);
            row.setFocusable(true); row.setClickable(true);
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() { public void onFocusChange(View v, boolean has) {
                rb.setColor(has ? 0xFFFFFFFF : 0x00FFFFFF); ((TextView) v).setTextColor(has ? 0xFF111111 : 0xFFFFFFFF); } });
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeOverlay(ov, false); a.run(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(2), 0, dp(2));
            panel.addView(row, lp);
            if (first == null) first = row;
        }
        FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(dp(360), -2, Gravity.CENTER);
        ov.addView(panel, lpp);
        openOverlay(ov, first);
    }

    interface Picker { void picked(String pkg); }

    /** Liste de toutes les applis. picker == null : ouvre l'appli ; sinon renvoie l'appli choisie. */
    void showApps(final Picker picker) {
        final PackageManager pm = getPackageManager();
        LinkedHashMap<String, ResolveInfo> map = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN); q.addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (!map.containsKey(p) && !p.equals(getPackageName())) map.put(p, ri);
            }
        }
        List<ResolveInfo> list = new ArrayList<>(map.values());
        Collections.sort(list, new java.util.Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) { return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()); } });
        final FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xFF0B0C10);
        ov.setClickable(true);
        // fond : la photo du moment, floutee et assombrie, facon Apple TV
        Bitmap blur = blurredWall();
        if (blur != null) { ImageView bgv = newWall(); bgv.setImageBitmap(blur); ov.addView(bgv); }
        View dim = new View(this); dim.setBackgroundColor(0x99000000);
        ov.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        // grille centree : tuiles 16:9, le nom n'apparait que sous la tuile choisie
        final int cols = 6, w = 168, hh = 95, mx = 10;
        int left = (1280 - cols * (w + 2 * mx)) / 2 + mx;
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false); sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false); col.setClipToPadding(false);
        col.setPadding(0, dp(122), 0, dp(60));
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        g.setClipChildren(false); g.setClipToPadding(false);
        View first = null;
        for (ResolveInfo ri : list) {
            final String pkg = ri.activityInfo.packageName;
            String label = ri.loadLabel(pm).toString();
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false); cell.setClipToPadding(false);
            Drawable ban = customBanner(pkg);
            if (ban == null) ban = ri.activityInfo.loadBanner(pm);
            FrameLayout tile = roundedFrame(12, ban != null ? 0xFF1E1E1E : 0x2EFFFFFF);
            if (ban != null) {
                ImageView iv = new ImageView(this); iv.setImageDrawable(ban); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            } else {
                // pas de banniere : icone + nom dans une tuile en verre
                LinearLayout l = new LinearLayout(this);
                l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(this); iv.setImageDrawable(ri.loadIcon(pm));
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(38), dp(38)); il.gravity = Gravity.CENTER_HORIZONTAL;
                l.addView(iv, il);
                TextView tn = text(label, 10, fMed, 0xFFFFFFFF); tn.setShadowLayer(0, 0, 0, 0);
                tn.setGravity(Gravity.CENTER); tn.setSingleLine(true); tn.setEllipsize(TextUtils.TruncateAt.END);
                tn.setPadding(dp(8), dp(6), dp(8), 0);
                l.addView(tn, new LinearLayout.LayoutParams(-1, -2));
                tile.addView(l, new FrameLayout.LayoutParams(-1, -1));
            }
            cell.addView(tile, new LinearLayout.LayoutParams(dp(w), dp(hh)));
            TextView name = text(label, 11, fMed, 0xFFFFFFFF);
            name.setGravity(Gravity.CENTER); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(12), 0, 0); name.setAlpha(0f);
            cell.addView(name, new LinearLayout.LayoutParams(dp(w + 2 * mx), -2));
            // la cellule fait deja w + 2*mx de large (place du nom) : pas de marge laterale en plus
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.setMargins(0, dp(6), 0, dp(6));
            tile.setTag(12);
            wire(tile, new Runnable() { public void run() {
                closeOverlay(ov, picker == null);
                if (picker != null) picker.picked(pkg); else launch(pkg); } }, 1.1f, name);
            if (picker == null) {
                tile.setLongClickable(true);
                tile.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) {
                    List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
                    l.add(t("Ouvrir", "Open")); a.add(new Runnable() { public void run() { closeOverlay(ov, false); launch(pkg); } });
                    if (!dockPkgs.contains(pkg) && dockPkgs.size() < layout) {
                        l.add(t("Ajouter au dock", "Add to dock")); a.add(new Runnable() { public void run() {
                            closeOverlay(ov, false); dockPkgs.add(pkg); saveConfig(); buildDock(dockPkgs.indexOf(pkg)); } });
                    } else if (!dockPkgs.contains(pkg)) {
                        l.add(t("Dock plein : remplacer une appli…", "Dock full: replace an app…")); a.add(new Runnable() { public void run() {
                            closeOverlay(ov, false); chooseSlotFor(pkg); } });
                    }
                    showMenu(appLabel(pkg), l, a); return true; } });
            }
            g.addView(cell, lp);
            if (first == null) first = tile;
        }
        // largeur fixe : en WRAP_CONTENT, GridLayout prend toute la largeur et la grille n'est plus centree
        col.addView(g, new LinearLayout.LayoutParams(dp(cols * (w + 2 * mx)), -2));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        ov.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        // en-tete fixe ; le degrade laisse passer les tuiles dessous proprement quand on fait defiler
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        ov.addView(fade, new FrameLayout.LayoutParams(-1, dp(118), Gravity.TOP));
        TextView hdr = text(picker == null ? t("Applications", "Apps") : t("Choisissez une appli", "Choose an app"), 30, fLight, 0xFFFFFFFF);
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lh.setMargins(dp(left), dp(44), 0, 0);
        ov.addView(hdr, lh);
        TextView sub = text(picker == null ? list.size() + t(" applis  ·  appui long sur OK : ajouter au dock", " apps  ·  long-press OK: add to dock")
                : t("Elle ira dans le dock", "It goes into the dock"), 11, fReg, 0x99FFFFFF);
        FrameLayout.LayoutParams ls = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        ls.setMargins(0, dp(60), dp(left), 0);
        ov.addView(sub, ls);
        openOverlay(ov, first);
        g.setScaleX(0.97f); g.setScaleY(0.97f);
        g.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Photo affichee, reduite puis floutee : fond des ecrans en surimpression. */
    Bitmap blurredWall() {
        try {
            Drawable d = (aOnTop ? wallA : wallB).getDrawable();   // showWall vient d'inverser aOnTop
            if (!(d instanceof BitmapDrawable)) return null;
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src == null || src.isRecycled()) return null;
            int w = 96, hh = Math.max(1, w * src.getHeight() / Math.max(1, src.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(src, w, hh, true);
            int[] px = new int[w * hh];
            small.getPixels(px, 0, w, 0, 0, w, hh);
            small.recycle();
            boxBlur(px, w, hh, 3); boxBlur(px, w, hh, 3);
            Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, hh);
            return out;
        } catch (Throwable t) { return null; }
    }

    /** Flou par moyenne, horizontal puis vertical (sur une image de 96 px de large, le cout est negligeable). */
    static void boxBlur(int[] px, int w, int h, int r) {
        int[] out = new int[px.length];
        for (int dir = 0; dir < 2; dir++) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int rr = 0, gg = 0, bb = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = dir == 0 ? Math.min(w - 1, Math.max(0, x + k)) : x;
                    int yy = dir == 1 ? Math.min(h - 1, Math.max(0, y + k)) : y;
                    int c = px[yy * w + xx];
                    rr += (c >> 16) & 0xFF; gg += (c >> 8) & 0xFF; bb += c & 0xFF; n++;
                }
                out[y * w + x] = 0xFF000000 | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
            System.arraycopy(out, 0, px, 0, px.length);
        }
    }

    void chooseSlotFor(final String pkg) {
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        for (int i = 0; i < dockPkgs.size(); i++) {
            final int idx = i;
            l.add(t("Remplacer ", "Replace ") + appLabel(dockPkgs.get(i)));
            a.add(new Runnable() { public void run() { dockPkgs.set(idx, pkg); saveConfig(); buildDock(idx); } });
        }
        showMenu(appLabel(pkg), l, a);
    }

    void openOverlay(View ov, View focus) {
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        overlays.add(ov);
        trapFocus();
        ov.setAlpha(0f); ov.animate().alpha(1f).setDuration(160).start();
        if (focus != null) focus.requestFocus();
    }

    void closeOverlay(View ov, boolean refocus) {
        root.removeView(ov);
        overlays.remove(ov);
        trapFocus();
        View back = overlays.isEmpty() ? returnFocus : null;
        if (overlays.isEmpty()) returnFocus = null;
        if (refocus) {
            if (!overlays.isEmpty()) overlays.get(overlays.size() - 1).requestFocus();
            else if (back != null && back.isAttachedToWindow()) back.requestFocus();
            else focusDock();
        }
    }

    /** Seule la surimpression du dessus prend le focus : sinon les fleches partent sur des tuiles cachees dessous. */
    void trapFocus() {
        dock.setDescendantFocusability(overlays.isEmpty() ? ViewGroup.FOCUS_BEFORE_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (int i = 0; i < overlays.size(); i++) {
            View o = overlays.get(i);
            if (o instanceof ViewGroup) ((ViewGroup) o).setDescendantFocusability(
                    i == overlays.size() - 1 ? ViewGroup.FOCUS_BEFORE_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    // ---------- Barre laterale des reglages ----------
    View returnFocus;   // tuile qui reprend le focus quand la derniere surimpression se ferme (ex. Reglages)

    /** Barre des reglages facon Google TV (classe SettingsPanel, aussi ouverte par-dessus les applis par PanelActivity).
     *  Chaque sous-menu est une surimpression de plus : RETOUR remonte d'un niveau, trapFocus bloque le reste. */
    void showSettingsPanel() {
        returnFocus = getCurrentFocus();
        new SettingsPanel(this, root, new SettingsPanel.Host() {
            public void push(View level, View focus) { openOverlay(level, focus); }
            public void pop() { closeTopOverlay(); }
            public void closeAll() {
                while (overlays.size() > 1) closeOverlay(overlays.get(overlays.size() - 1), false);
                closeTopOverlay();
            }
        }).show();
    }

    void startSafe(Intent i) {
        try { startActivity(i); } catch (Exception e) { toast(t("Impossible d'ouvrir", "Cannot open")); }
    }

    void closeTopOverlay() {
        if (overlays.isEmpty()) return;
        View ov = overlays.get(overlays.size() - 1);
        closeOverlay(ov, true);
    }

    void focusDock() {
        if (!bigTiles.isEmpty()) bigTiles.get(0).requestFocus();
        else if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { closeTopOverlay(); return true; } // l'accueil ne se ferme jamais
        // touche Source / TV : sur certaines TV, seul l'accueil d'origine la gerait
        if ((code == KeyEvent.KEYCODE_TV_INPUT || code == KeyEvent.KEYCODE_TV) && overlays.isEmpty() && moveIndex < 0) {
            openInputs();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (!overlays.isEmpty()) return true;
            View f = getCurrentFocus();
            int idx = f != null ? bigTiles.indexOf(f) : -1;
            showTileMenu(idx);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() { closeTopOverlay(); }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // touche Accueil : ferme tout ce qui est ouvert
        if (moveIndex >= 0) endMove();
        while (!overlays.isEmpty()) closeOverlay(overlays.get(overlays.size() - 1), false);
        focusDock();
    }

    // =====================================================================
    // Lancements
    String[] findInput() {
        for (String[] in : KNOWN_INPUTS) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(in[0], 0);
                if (!ai.enabled) continue;
                if (in[1] != null || launchIntent(in[0]) != null) return in;
            } catch (Exception ignored) { }
        }
        return null;
    }

    void openInput(String[] in) {
        if (in[1] != null) {
            try {
                Intent i = new Intent(); i.setComponent(new ComponentName(in[0], in[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return;
            } catch (Exception ignored) { }
        }
        launch(in[0]);
    }

    /** Menu des entrees du systeme s'il existe, sinon l'appli TV de la marque. */
    void openInputs() {
        Intent vi = new Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (vi.resolveActivity(getPackageManager()) != null) {
            try { startActivity(vi); return; } catch (Exception ignored) { }
        }
        String[] in = findInput();
        if (in != null) openInput(in);
        else toast(t("Aucun menu des entrées trouvé sur cette TV", "No inputs menu found on this TV"));
    }

    Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    void launch(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) { toast(t("Appli introuvable", "App not found")); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException e) { toast(t("Impossible d'ouvrir", "Cannot open")); }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    ImageView newWall() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    TextView text(String s, float size, Typeface tf, int color) {
        TextView tv = new TextView(this);
        tv.setText(s); tv.setTextColor(color); tv.setTypeface(tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density);
        tv.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    // =====================================================================
    // Fonds d'ecran
    void loadWalls() {
        walls.clear();
        wallsFromFiles = false;
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "ZenHome");
            File[] fs = dir.listFiles();
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")))
                        walls.add(f.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) { }
        if (!walls.isEmpty()) { wallsFromFiles = true; }
        else {
            try {
                String[] a = getAssets().list("walls");
                if (a != null) { Arrays.sort(a); for (String n : a) { String l = n.toLowerCase(Locale.ROOT); if (l.endsWith(".jpg") || l.endsWith(".png")) walls.add(n); } }
            } catch (Exception ignored) { }
        }
        wallIdx = walls.isEmpty() ? 0 : (int) ((System.currentTimeMillis() / rotationMs) % walls.size());
    }

    Bitmap decode(String name) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            int maxW = Math.max(getResources().getDisplayMetrics().widthPixels, 1280);
            if (wallsFromFiles) {
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(name, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= maxW) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeFile(name, o);
            }
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            InputStream is = getAssets().open("walls/" + name);
            Bitmap b = BitmapFactory.decodeStream(is, null, o);
            is.close();
            return b;
        } catch (Throwable t) { return null; }
    }

    void showWall(boolean instant) {
        if (walls.isEmpty()) return;
        final ImageView next = aOnTop ? wallB : wallA;
        final ImageView cur = aOnTop ? wallA : wallB;
        Bitmap bm = decode(walls.get(wallIdx));
        if (bm == null) return;
        Drawable old = next.getDrawable();
        next.setImageBitmap(bm);
        if (old instanceof BitmapDrawable) {
            Bitmap ob = ((BitmapDrawable) old).getBitmap();
            if (ob != null && ob != bm) ob.recycle();
        }
        next.bringToFront(); // uniquement dans le calque des photos
        next.setScaleX(1f); next.setScaleY(1f);
        next.animate().cancel();
        if (instant) { next.setAlpha(1f); cur.setAlpha(0f); }
        else {
            next.setAlpha(0f);
            android.animation.ObjectAnimator.ofFloat(next, "alpha", 0f, 1f).setDuration(FADE_MS).start();
        }
        next.animate().scaleX(1.06f).scaleY(1.06f).setDuration(rotationMs + FADE_MS)
                .setInterpolator(new LinearInterpolator()).start();
        aOnTop = !aOnTop;
    }

    final Runnable rotate = new Runnable() {
        public void run() {
            if (walls.size() > 1) { wallIdx = (wallIdx + 1) % walls.size(); showWall(false); }
            h.postDelayed(this, rotationMs);
        }
    };

    final Runnable tick = new Runnable() {
        public void run() {
            Date n = new Date();
            clock.setText(DateFormat.is24HourFormat(Home.this) ? DateFormat.format("HH:mm", n) : DateFormat.format("h:mm", n));
            Locale loc = Locale.getDefault();
            String d = new SimpleDateFormat(fr ? "EEEE d MMMM" : "EEEE, MMMM d", loc).format(n);
            date.setText(d.substring(0, 1).toUpperCase(loc) + d.substring(1));
            h.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        // la configuration a pu changer via le kit pendant que l'accueil etait en fond
        String rev = setting("zenhome_rev");
        if (rev != null && !rev.equals(prefs.getString("rev", null)) && moveIndex < 0 && overlays.isEmpty()) {
            loadConfig(); applyTitle(); buildDock(0);
        }
        h.removeCallbacksAndMessages(null);
        h.post(tick);
        h.postDelayed(rotate, rotationMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null); // rien ne tourne pendant qu'une appli est ouverte
    }

    @Override
    protected void onStop() {
        super.onStop();
        // libere la memoire des photos pendant qu'on regarde une video
        for (ImageView v : new ImageView[]{wallA, wallB}) {
            v.animate().cancel();
            Drawable d = v.getDrawable(); v.setImageDrawable(null);
            if (d instanceof BitmapDrawable) { Bitmap b = ((BitmapDrawable) d).getBitmap(); if (b != null) b.recycle(); }
        }
        released = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (released) { released = false; loadWalls(); showWall(true); }
    }

    /** Icone "toutes les applis" : grille 3x3 centree. */
    static class GridIcon extends Drawable {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        GridIcon() { p.setColor(0xFFFFFFFF); }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float size = Math.min(b.width(), b.height()) * 0.74f;
            float cell = size / 3f, sq = cell * 0.66f, r = sq * 0.3f;
            float x0 = b.exactCenterX() - size / 2f, y0 = b.exactCenterY() - size / 2f;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                float x = x0 + j * cell + (cell - sq) / 2f, y = y0 + i * cell + (cell - sq) / 2f;
                c.drawRoundRect(x, y, x + sq, y + sq, r, r, p);
            }
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
