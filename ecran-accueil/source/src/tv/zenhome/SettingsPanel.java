package tv.zenhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Barre laterale des reglages, facon Google TV : panneau sombre a droite, une ligne par reglage
 * (pastille ronde, titre, valeur), sous-menus empiles. RETOUR (ou fleche gauche dans un sous-menu) remonte d'un niveau.
 * Autonome : se construit dans n'importe quelle Activity + FrameLayout (Home, ou PanelActivity par-dessus une appli).
 *
 * Sur les TCL, une appli ordinaire ne peut ni lire ni changer le mode image, le mode son ou la minuterie
 * (les services TCL refusent ses appels) : ces lignes ouvrent la liste TCL voulue, une fenetre de
 * com.tcl.settings/.ShowWindowService qui s'affiche par-dessus n'importe quelle appli.
 */
class SettingsPanel {

    /** Pile de surimpressions de l'hote : seul le niveau du dessus prend le focus. */
    interface Host {
        void push(View level, View focus);
        /** Retire le niveau du dessus ; sous le premier niveau, la barre est fermee. */
        void pop();
        /** Ferme toute la barre (une page des parametres ou une fenetre TCL s'ouvre). */
        void closeAll();
    }

    static final int PW = 404;   // largeur du panneau, sur 1280 unites
    // couleurs de l'ecran Parametres de la TV (Google TV)
    static final int BG = 0xFF1E2127, TXT = 0xFFE8EAED, SUB = 0xFF9AA0A6, BADGE = 0xFF30343C, ICON = 0xFFDADCE0;
    static final int F_BG = 0xFFE8EAED, F_TXT = 0xFF1F1F1F, F_SUB = 0xFF5F6368, F_BADGE = 0xFFD3E3FD, F_ICON = 0xFF0B57D0;
    static final String TCL_PKG = "com.tcl.settings", TCL_WINDOW = "com.tcl.settings.ShowWindowService";
    // icones Material du night shift : dark_mode, light_mode, wb_twilight
    static final String NIGHT_ICON = String.valueOf((char) 0xe51c), DAY_ICON = String.valueOf((char) 0xe518),
            SCHEDULE_ICON = String.valueOf((char) 0xe1c6);

    static Typeface fReg, fMed, fIcons;
    final Activity act;
    final FrameLayout root;
    final Host host;
    final float density;
    final boolean fr;

    SettingsPanel(Activity act, FrameLayout root, Host host) {
        this.act = act; this.root = root; this.host = host;
        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        density = Math.max(dm.widthPixels, dm.heightPixels) / 1280f;
        fr = "fr".equals(Locale.getDefault().getLanguage());
        if (fReg == null) {
            fReg = font("Poppins-Regular.ttf"); fMed = font("Poppins-Medium.ttf"); fIcons = font("MaterialIconsRound-Regular.otf");
            if (fReg == null) fReg = Typeface.SANS_SERIF;
            if (fMed == null) fMed = fReg;
        }
    }

    Typeface font(String f) { try { return Typeface.createFromAsset(act.getAssets(), f); } catch (Exception e) { return null; } }
    int dp(float v) { return Math.round(v * density); }
    String t(String frTxt, String enTxt) { return fr ? frTxt : enTxt; }
    void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_SHORT).show(); }

    TextView text(String s, float size, Typeface tf, int color) {
        TextView tv = new TextView(act);
        tv.setText(s); tv.setTextColor(color); tv.setTypeface(tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    // =====================================================================
    // Premier niveau
    void show() {
        Level lv = new Level(false);
        View scrim = new View(act);
        scrim.setBackgroundColor(0x8C000000);
        lv.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        lv.scrim = scrim;
        LinearLayout col = panel(lv);

        // en-tete : nom de la TV, heure et date
        LinearLayout hd = new LinearLayout(act);
        hd.setOrientation(LinearLayout.VERTICAL);
        hd.setPadding(dp(14), dp(44), dp(14), dp(20));
        TextView name = text(deviceName(), 24, fMed, TXT);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        hd.addView(name);
        Date n = new Date();
        String d = new SimpleDateFormat(fr ? "EEEE d MMMM" : "EEEE, MMMM d", Locale.getDefault()).format(n);
        String time = DateFormat.format(DateFormat.is24HourFormat(act) ? "HH:mm" : "h:mm", n).toString();
        TextView when = text(time + "  ·  " + d.substring(0, 1).toUpperCase(Locale.getDefault()) + d.substring(1), 12, fReg, SUB);
        when.setPadding(0, dp(8), 0, 0);
        hd.addView(when);
        col.addView(hd);

        final boolean tcl = tclWindows();
        if (tcl) add(col, lv, new Row("\ue3f4", t("Image", "Picture"), t("Mode image, luminosité, format", "Picture mode, brightness, aspect"), true,
                new Runnable() { public void run() { showPicture(); } }));
        else addIf(col, lv, new Intent(Settings.ACTION_DISPLAY_SETTINGS), "\ue3f4", t("Image", "Picture"), t("Mode image, luminosité", "Picture mode, brightness"));

        if (tcl) {
            final Row night = new Row(NIGHT_ICON, "Night shift", nightText(), true, null);
            night.action = new Runnable() { public void run() { showNightShift(night); } };
            add(col, lv, night);
        }

        final Row sound = new Row("\ue050", t("Son", "Sound"), volumeText(), true, null);
        sound.action = new Runnable() { public void run() { showSound(sound); } };
        add(col, lv, sound);

        if (!hwInputs().isEmpty()) {
            String cur = currentInputLabel();
            add(col, lv, new Row("\ue890", t("Entrées", "Inputs"), cur != null ? cur : t("TV, HDMI, AV", "TV, HDMI, AV"), true,
                    new Runnable() { public void run() { showInputs(); } }));
        }
        addIf(col, lv, new Intent("com.android.tv.settings.SLEEP_TIMER"), "\uef44", t("Minuterie de veille", "Sleep timer"),
                t("Éteindre la TV après un délai", "Turn the TV off after a delay"));
        if (tcl) add(col, lv, new Row("\ue647", t("Éteindre l'écran", "Turn screen off"),
                t("Le son continue, une touche rallume", "Sound keeps playing, any key wakes it"), false, tclWindow("AudioOnly")));
        if (BluetoothAdapter.getDefaultAdapter() != null)
            add(col, lv, new Row("\ue1a7", "Bluetooth", bluetoothText(), true, new Runnable() { public void run() { showBluetooth(); } }));
        String[] net = network();
        addIf(col, lv, new Intent(Settings.ACTION_WIFI_SETTINGS), net[0], t("Réseau", "Network"), net[1]);
        divider(col);
        addIf(col, lv, new Intent(Settings.ACTION_SETTINGS), "\ue8b8", t("Tous les paramètres", "All settings"),
                t("Comptes, applications, système", "Accounts, apps, system"));

        host.push(lv, lv.first);
        // entree : le panneau glisse depuis le bord droit, le voile apparait
        lv.slider.setTranslationX(dp(PW + 32));
        lv.slider.animate().translationX(0).setDuration(280).setInterpolator(new DecelerateInterpolator(2f)).start();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(240).start();
    }

    // =====================================================================
    // Sous-menus
    void showPicture() {
        Level lv = new Level(true);
        LinearLayout col = panel(lv);
        title(col, t("Image", "Picture"));
        add(col, lv, new Row("\ue65f", t("Mode image", "Picture mode"), t("Dynamique, Standard, Film, Jeu…", "Vivid, Standard, Movie, Game…"), false, tclWindow("picturemode")));
        add(col, lv, new Row("\ue518", t("Luminosité", "Brightness"), t("Rétroéclairage de l'écran", "Screen backlight"), false, tclWindow("brightness")));
        add(col, lv, new Row("\ue85b", t("Format d'écran", "Aspect ratio"), t("16:9, 4:3, zoom…", "16:9, 4:3, zoom…"), false, tclWindow("Zoom")));
        add(col, lv, new Row("\ue429", t("Tous les réglages d'image", "All picture settings"), t("Saturation, contraste, avancés", "Saturation, contrast, advanced"), false, tclWindow("picture")));
        enter(lv);
    }

    /** Night shift (classe NightShift) : le niveau suit le soleil, les fleches le reglent a la main, OK le rend a l'automatique. */
    void showNightShift(final Row parent) {
        final Level lv = new Level(true);
        LinearLayout col = panel(lv);
        title(col, "Night shift");
        final NightRow night = new NightRow();
        final DayRow day = new DayRow();
        night.onChange = day.onChange = new Runnable() { public void run() { parent.sub.setText(nightText()); } };
        add(col, lv, night);
        add(col, lv, day);
        long[] e = NightShift.evening(System.currentTimeMillis());
        long rise = NightShift.sun(java.util.Calendar.getInstance(), true);
        if (rise < System.currentTimeMillis()) rise += 86400000L;   // a une minute pres : le lever de demain
        add(col, lv, new Row(SCHEDULE_ICON, t("Programme", "Schedule"),
                t("Coucher ", "Sunset ") + hm(e[0]) + t("  ·  max à ", "  ·  full at ") + hm(e[1]) + t("  ·  lever ", "  ·  sunrise ") + hm(rise), false,
                new Runnable() { public void run() { NightShift.setAuto(act); night.update(); NightShift.apply(act); } }));
        // le niveau automatique avance pendant que la barre est ouverte
        lv.post(new Runnable() { public void run() {
            if (!lv.isAttachedToWindow()) return;
            night.update();
            lv.postDelayed(this, 5000);
        } });
        enter(lv);
    }

    String nightText() {
        int p = NightShift.level(act);
        if (NightShift.manual(act)) return t("Manuel  ·  ", "Manual  ·  ") + p + " %";
        if (p == 0) return t("Auto  ·  dès ", "Auto  ·  from ") + hm(NightShift.evening(System.currentTimeMillis())[0]);
        return "Auto  ·  " + p + " %";
    }

    String hm(long ms) { return DateFormat.getTimeFormat(act).format(new Date(ms)); }

    void showSound(final Row parent) {
        final Level lv = new Level(true);
        LinearLayout col = panel(lv);
        title(col, t("Son", "Sound"));
        final AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        final VolumeRow vol = new VolumeRow(am);
        vol.onChange = new Runnable() { public void run() { parent.sub.setText(volumeText()); } };
        add(col, lv, vol);
        if (tclWindows()) {
            add(col, lv, new Row("\ue1b8", t("Mode son", "Sound mode"), t("Standard, Film, Musique, Voix, Jeu…", "Standard, Movie, Music, Voice, Game…"), false, tclWindow("sound_preset")));
            add(col, lv, new Row("\ue32d", t("Sortie audio", "Audio output"), t("Haut-parleurs, casque, ARC, Bluetooth", "Speakers, headphones, ARC, Bluetooth"), false, tclWindow("audio_output")));
            add(col, lv, new Row("\ue429", t("Tous les réglages du son", "All sound settings"), t("Retard audio, traitement, avancés", "Audio delay, processing, advanced"), false, tclWindow("sound")));
        }
        enter(lv);
    }

    /** Entrees de la TV (TV, HDMI, AV) : le nom donne dans les parametres, ou l'appareil branche (HDMI-CEC). */
    void showInputs() {
        Level lv = new Level(true);
        LinearLayout col = panel(lv);
        title(col, t("Entrées", "Inputs"));
        TvInputManager tim = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
        String cur = prop("persist.sys.curInput");
        for (final TvInputInfo ii : hwInputs()) {
            String port = ii.loadLabel(act).toString();
            String custom = custom(ii);
            String dev = cecName(ii);
            String ttl = custom != null ? custom : (dev != null ? dev : port);
            String st = ttl.equals(port) ? "" : port;
            if (dev != null && !ttl.equals(dev)) st = st + (st.isEmpty() ? "" : "  ·  ") + dev;
            if (tim.getInputState(ii.getId()) == TvInputManager.INPUT_STATE_DISCONNECTED) st = st + (st.isEmpty() ? "" : "  ·  ") + t("Débranché", "Disconnected");
            String icon = ii.getType() == TvInputInfo.TYPE_TUNER ? "\ue639" : (ii.getType() == TvInputInfo.TYPE_HDMI ? "\ue8c2" : "\ue8c1");
            Row r = new Row(icon, ttl, st.isEmpty() ? null : st, false, new Runnable() { public void run() { launch(inputIntent(ii)); } });
            add(col, lv, r);
            if (ii.getId().equals(cur)) { r.setEnd("\ue5ca", true); lv.first = r; }   // le focus part de l'entree actuelle
        }
        enter(lv);
    }

    /** TCL : l'appli TV avec l'extra inputId, comme la barre des sources TCL (marche aussi pour TV/antenne) ;
     *  ailleurs, VIEW sur l'URI de l'entree. */
    Intent inputIntent(TvInputInfo ii) {
        Intent tcl = new Intent().setClassName("com.tcl.tv", "com.tcl.tv.TVActivity").putExtra("inputId", ii.getId());
        if (resolves(tcl)) return tcl;
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(ii.getId()));
    }

    /** Appareils Bluetooth associes (fiche de l'appareil au clic) et association d'un nouvel appareil. Pas d'interrupteur : couper le Bluetooth couperait la telecommande. */
    void showBluetooth() {
        Level lv = new Level(true);
        LinearLayout col = panel(lv);
        title(col, "Bluetooth");
        // associer d'abord (comme Google TV) : le focus de depart tombe sur une action sans risque
        Intent pair = new Intent("com.google.android.intent.action.CONNECT_INPUT");
        if (resolves(pair)) add(col, lv, new Row("\ue145", t("Associer un appareil", "Pair a device"), t("Casque, manette, clavier…", "Headphones, gamepad, keyboard…"), false, launcher(pair)));
        for (final BluetoothDevice d : bonded()) {
            final String name = btName(d);
            String icon = btIcon(d), state = connected(d) ? t("Connecté", "Connected") : t("Déconnecté", "Disconnected");
            boolean remote = isRemote(d);   // la telecommande garde son nom de modele en sous-titre
            Row r = new Row(icon, remote ? t("Télécommande", "Remote") : name, remote ? state + "  ·  " + name : state, false, new Runnable() { public void run() {
                Intent i = new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.BluetoothAccessoryActivity")
                        .putExtra("accessory_address", d.getAddress()).putExtra("accessory_name", name);
                launch(i); } });
            add(col, lv, r);
        }
        enter(lv);
    }

    // =====================================================================
    // Construction des niveaux
    /** Un niveau : plein ecran, garde la ligne qui avait le focus pour y revenir au RETOUR. */
    class Level extends FrameLayout {
        final boolean sub;
        View last, first, slider, scrim;
        boolean closing;
        Level(boolean sub) {
            super(act);
            this.sub = sub;
            setClickable(true); setFocusable(false);   // cliquable = focusable par defaut depuis Android 8
        }
        @Override protected boolean onRequestFocusInDescendants(int dir, Rect r) {
            if (last != null && last.isAttachedToWindow() && last.requestFocus()) return true;
            return super.onRequestFocusInDescendants(dir, r);
        }
    }

    /** Panneau du niveau (ombre a gauche, fond sombre, colonne defilante) ; renvoie la colonne a remplir. */
    LinearLayout panel(Level lv) {
        LinearLayout slider = new LinearLayout(act);
        slider.setOrientation(LinearLayout.HORIZONTAL);
        View shadow = new View(act);
        shadow.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00000000, 0x66000000}));
        slider.addView(shadow, new LinearLayout.LayoutParams(dp(32), -1));
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(BG);
        sv.setVerticalScrollBarEnabled(false); sv.setFillViewport(true);
        sv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, dp(16), dp(32));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        slider.addView(sv, new LinearLayout.LayoutParams(dp(PW), -1));
        lv.addView(slider, new FrameLayout.LayoutParams(dp(PW + 32), -1, Gravity.END));
        lv.slider = slider;
        return col;
    }

    /** En-tete d'un sous-menu : rappel "Reglages" puis le titre. */
    void title(LinearLayout col, String s) {
        LinearLayout hd = new LinearLayout(act);
        hd.setOrientation(LinearLayout.VERTICAL);
        hd.setPadding(dp(14), dp(44), dp(14), dp(20));
        TextView crumb = text(t("RÉGLAGES", "SETTINGS"), 10.5f, fMed, SUB);
        crumb.setLetterSpacing(0.08f);
        hd.addView(crumb);
        TextView tv = text(s, 24, fMed, TXT);
        tv.setPadding(0, dp(8), 0, 0);
        hd.addView(tv);
        col.addView(hd);
    }

    void divider(LinearLayout col) {
        View v = new View(act); v.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.setMargins(dp(14), dp(10), dp(14), dp(10));
        col.addView(v, lp);
    }

    void add(LinearLayout col, final Level lv, final Row r) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(2), 0, dp(2));
        col.addView(r, lp);
        if (lv.first == null) lv.first = r;
        r.setOnFocusChangeListener(new View.OnFocusChangeListener() { public void onFocusChange(View v, boolean has) {
            r.paint(has); if (has) lv.last = v; } });
        r.setOnKeyListener(new View.OnKeyListener() { public boolean onKey(View v, int code, KeyEvent e) { return key(lv, r, code, e); } });
    }

    /** Ligne vers une page des parametres, seulement si la TV la connait. */
    void addIf(LinearLayout col, Level lv, Intent i, String icon, String title, String sub) {
        if (resolves(i)) add(col, lv, new Row(icon, title, sub, false, launcher(i)));
    }

    /** RETOUR remonte d'un niveau ; dans un sous-menu, la fleche gauche aussi ; la fleche droite ouvre un sous-menu. */
    boolean key(Level lv, Row r, int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) back(lv);
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (r instanceof BarRow && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            ((BarRow) r).step(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && lv.sub) { back(lv); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && r.menu) { r.performClick(); return true; }
        return false;
    }

    /** Sortie animee du niveau, puis l'hote le retire. */
    void back(final Level lv) {
        if (lv.closing) return;
        lv.closing = true;
        lv.slider.animate().translationX(dp(lv.sub ? 56 : PW + 32)).alpha(lv.sub ? 0f : 1f)
                .setDuration(lv.sub ? 140 : 200).setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() { public void run() { host.pop(); } }).start();
        if (lv.scrim != null) lv.scrim.animate().alpha(0f).setDuration(200).start();
    }

    /** Entree d'un sous-menu : glisse un peu depuis la droite par-dessus le niveau precedent. */
    void enter(Level lv) {
        host.push(lv, lv.first);
        lv.slider.setTranslationX(dp(56)); lv.slider.setAlpha(0f);
        lv.slider.animate().translationX(0).alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    // =====================================================================
    // Lignes
    /** Ligne de la barre : pastille ronde, titre, sous-titre, chevron (sous-menu) ou coche a droite. */
    class Row extends LinearLayout {
        final TextView icon, title, sub, end;
        final LinearLayout texts;
        final GradientDrawable bg = new GradientDrawable(), badge = new GradientDrawable();
        final boolean menu;
        Runnable action;
        boolean endAccent;

        Row(String ic, String ttl, String st, boolean menu, Runnable action) {
            super(act);
            this.menu = menu; this.action = action;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(11), dp(14), dp(11));
            setMinimumHeight(dp(60));
            bg.setCornerRadius(dp(14)); bg.setColor(0x00000000);
            setBackground(bg);
            FrameLayout b = new FrameLayout(act);
            badge.setShape(GradientDrawable.OVAL); badge.setColor(BADGE);
            b.setBackground(badge);
            icon = text(ic, 20, fIcons != null ? fIcons : fReg, ICON);
            icon.setGravity(Gravity.CENTER);
            if (fIcons == null) icon.setVisibility(GONE);
            b.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            addView(b, new LayoutParams(dp(38), dp(38)));
            texts = new LinearLayout(act);
            texts.setOrientation(VERTICAL);
            title = text(ttl, 14.5f, fReg, TXT);
            title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            sub = text(st == null ? "" : st, 11.5f, fReg, SUB);
            sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(4), 0, 0);
            if (st == null) sub.setVisibility(GONE);
            texts.addView(sub);
            LayoutParams lt = new LayoutParams(0, -2, 1f);
            lt.setMargins(dp(14), 0, dp(8), 0);
            addView(texts, lt);
            end = text(menu ? "\ue5cc" : "", 20, fIcons != null ? fIcons : fReg, SUB);
            if (!menu || fIcons == null) end.setVisibility(GONE);
            addView(end, new LayoutParams(-2, -2));
            setFocusable(true); setClickable(true);
            if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);   // pas de voile gris d'Android 8 par-dessus la pilule
            setOnClickListener(new OnClickListener() { public void onClick(View v) { if (Row.this.action != null) Row.this.action.run(); } });
        }

        /** Element de droite : icone (coche) en couleur d'accent, ou valeur en texte. */
        void setEnd(String s, boolean iconFont) {
            end.setText(s);
            end.setTypeface(iconFont && fIcons != null ? fIcons : fMed);
            end.setTextSize(TypedValue.COMPLEX_UNIT_PX, (iconFont ? 20 : 14) * density);
            end.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
            endAccent = iconFont;
            paint(hasFocus());
        }

        /** Ligne choisie : pilule claire, texte sombre, pastille bleue. */
        void paint(boolean f) {
            bg.setColor(f ? F_BG : 0x00000000);
            badge.setColor(f ? F_BADGE : BADGE);
            icon.setTextColor(f ? F_ICON : ICON);
            title.setTextColor(f ? F_TXT : TXT);
            sub.setTextColor(f ? F_SUB : SUB);
            end.setTextColor(endAccent ? (f ? F_ICON : 0xFF8AB4F8) : (f ? F_SUB : SUB));
        }
    }

    /** Ligne a curseur : barre de progression sous le titre, fleches gauche/droite pour regler. */
    abstract class BarRow extends Row {
        final View fill, rest;
        final GradientDrawable fillBg = new GradientDrawable(), restBg = new GradientDrawable();
        Runnable onChange;

        BarRow(String ic, String ttl) {
            super(ic, ttl, null, false, null);
            LinearLayout bar = new LinearLayout(act);
            bar.setOrientation(HORIZONTAL);
            fill = new View(act); rest = new View(act);
            fillBg.setCornerRadius(dp(2)); restBg.setCornerRadius(dp(2));
            fill.setBackground(fillBg); rest.setBackground(restBg);
            bar.addView(fill, new LayoutParams(0, -1, 0f));
            bar.addView(rest, new LayoutParams(0, -1, 1f));
            LayoutParams lb = new LayoutParams(-1, dp(4));
            lb.setMargins(0, dp(9), 0, dp(2));
            texts.addView(bar, lb);
            paint(false);
        }

        abstract void step(int dir);

        void bar(int v, int max) {
            ((LayoutParams) fill.getLayoutParams()).weight = v;
            ((LayoutParams) rest.getLayoutParams()).weight = max - v;
            fill.requestLayout();
        }

        @Override void paint(boolean f) {
            super.paint(f);
            if (fillBg == null) return;   // appele par le constructeur de Row
            fillBg.setColor(f ? F_ICON : TXT);
            restBg.setColor(f ? 0xFFC4C7C5 : 0xFF3C4048);
        }
    }

    /** Volume : fleches gauche/droite pour regler, OK pour couper le son. Agit directement (AudioManager, sans permission). */
    class VolumeRow extends BarRow {
        final AudioManager am;

        VolumeRow(AudioManager am) {
            super("", t("Volume", "Volume"));
            this.am = am;
            action = new Runnable() { public void run() {
                VolumeRow.this.am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0); update(); } };
            update();
        }

        void step(int dir) {
            if (muted()) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            update();
        }

        boolean muted() { return Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC); }

        void update() {
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            boolean m = muted();
            bar(m ? 0 : v, max);
            icon.setText(m ? "" : "");
            setEnd(m ? t("Muet", "Muted") : String.valueOf(v), false);
            if (onChange != null) onChange.run();
        }
    }

    /** Fenetres TCL envoyees une fois les fleches relachees : chaque envoi ouvre le curseur TCL un instant. */
    final android.os.Handler ui = new android.os.Handler();
    final Runnable applyNight = new Runnable() { public void run() { NightShift.apply(act); } };

    void applyNightSoon(boolean veilNow) {
        if (veilNow) Keys.veil(NightShift.veil(NightShift.level(act)));   // le voile suit tout de suite
        ui.removeCallbacks(applyNight);
        ui.postDelayed(applyNight, 1200);   // pas root.postDelayed : la barre peut se fermer avant
    }

    /** Niveau du night shift : suit le soleil (Auto), les fleches le reglent a la main, OK le rend a l'automatique. */
    class NightRow extends BarRow {
        NightRow() {
            super(NIGHT_ICON, "Night shift");
            action = new Runnable() { public void run() { NightShift.setAuto(act); update(); applyNightSoon(true); } };
            update();
        }

        void step(int dir) {
            NightShift.setManual(act, (NightShift.level(act) + 5) / 10 * 10 + dir * 10);
            update();
            applyNightSoon(true);
        }

        void update() {
            int p = NightShift.level(act);
            bar(p, 100);
            sub.setVisibility(VISIBLE);
            sub.setText(NightShift.manual(act) ? t("Manuel jusqu'au prochain lever ou coucher  ·  OK : auto", "Manual until next sunrise or sunset  ·  OK: auto")
                    : t("Auto, suit le soleil  ·  flèches pour régler", "Auto, follows the sun  ·  arrows to adjust"));
            setEnd(p + " %", false);
            if (onChange != null) onChange.run();
        }
    }

    /** Retroeclairage TCL hors night shift (0-100) : le point de depart de la rampe du soir. */
    class DayRow extends BarRow {
        DayRow() {
            super(DAY_ICON, t("Luminosité du jour", "Daytime brightness"));
            sub.setVisibility(VISIBLE);
            sub.setText(t("Rétroéclairage hors night shift", "Backlight outside night shift"));
            update();
        }

        void step(int dir) {
            NightShift.setDay(act, NightShift.day(act) + dir * 5);
            update();
            applyNightSoon(false);
        }

        void update() {
            int d = NightShift.day(act);
            bar(d, 100);
            setEnd(String.valueOf(d), false);
            if (onChange != null) onChange.run();
        }
    }

    // =====================================================================
    // Actions et etat de la TV
    /** Les fenetres de reglage TCL (mode image, son, sortie audio, ecran eteint...) sont-elles la ? */
    boolean tclWindows() {
        Intent i = new Intent("com.tcl.settings.SHOW_WINDOW").setClassName(TCL_PKG, TCL_WINDOW);
        try { return act.getPackageManager().resolveService(i, 0) != null; } catch (Exception e) { return false; }
    }

    /** Ouvre une fenetre TCL : picturemode, brightness, Zoom, picture, sound_preset, audio_output, sound, AudioOnly. */
    Runnable tclWindow(final String type) {
        return new Runnable() { public void run() {
            Intent i = new Intent("com.tcl.settings.SHOW_WINDOW").setClassName(TCL_PKG, TCL_WINDOW).putExtra("Type", type);
            try {
                if (act.startService(i) == null) throw new IllegalStateException();
                host.closeAll();
            } catch (Exception e) { toast(t("Impossible d'ouvrir", "Cannot open")); }
        } };
    }

    Runnable launcher(final Intent i) { return new Runnable() { public void run() { launch(i); } }; }

    void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { act.startActivity(i); host.closeAll(); } catch (Exception e) { toast(t("Impossible d'ouvrir", "Cannot open")); }
    }

    boolean resolves(Intent i) { return i.resolveActivity(act.getPackageManager()) != null; }

    String deviceName() {
        String n = null;
        try { n = Settings.Global.getString(act.getContentResolver(), "device_name"); } catch (Throwable ignored) { }
        if (n == null || n.trim().isEmpty() || "null".equals(n)) n = Build.MODEL;
        return n == null ? "" : n.trim();
    }

    static String prop(String k) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, k); }
        catch (Throwable e) { return ""; }
    }

    String volumeText() {
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC)) return t("Son coupé", "Muted");
        return t("Volume ", "Volume ") + am.getStreamVolume(AudioManager.STREAM_MUSIC) + t("  ·  mode son, sortie audio", "  ·  sound mode, output");
    }

    /** Entrees physiques de la TV, dans l'ordre TV, HDMI 1..n, AV ; sans les entrees d'applis ni les appareils CEC. */
    List<TvInputInfo> hwInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo ii : tim.getTvInputList()) {
                if (ii.getParentId() != null || ii.isHidden(act)) continue;
                ApplicationInfo ai = act.getPackageManager().getApplicationInfo(ii.getServiceInfo().packageName, 0);
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;   // ex. Fubo/Molotov
                out.add(ii);
            }
            final Context c = act;
            Collections.sort(out, new java.util.Comparator<TvInputInfo>() { public int compare(TvInputInfo a, TvInputInfo b) {
                int ra = rank(a), rb = rank(b);
                return ra != rb ? ra - rb : a.loadLabel(c).toString().compareToIgnoreCase(b.loadLabel(c).toString()); } });
        } catch (Throwable ignored) { }
        return out;
    }

    /** Nom donne a l'entree dans les parametres de la TV (ex. "Console de jeu" pour HDMI 1). */
    String custom(TvInputInfo ii) {
        try { CharSequence c = ii.loadCustomLabel(act); return c == null || c.length() == 0 ? null : c.toString(); } catch (Throwable e) { return null; }
    }

    static int rank(TvInputInfo i) { return i.getType() == TvInputInfo.TYPE_TUNER ? 0 : (i.getType() == TvInputInfo.TYPE_HDMI ? 1 : 2); }

    /** Nom de l'appareil HDMI-CEC branche sur cette entree (ex. PlayStation 5). */
    String cecName(TvInputInfo parent) {
        try {
            TvInputManager tim = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo ii : tim.getTvInputList())
                if (parent.getId().equals(ii.getParentId())) return ii.loadLabel(act).toString();
        } catch (Throwable ignored) { }
        return null;
    }

    /** Derniere entree choisie (propriete TCL lisible par tous), avec le nom donne par l'utilisateur. */
    String currentInputLabel() {
        String cur = prop("persist.sys.curInput");
        if (cur == null || cur.isEmpty()) return null;
        for (TvInputInfo ii : hwInputs()) {
            if (!ii.getId().equals(cur)) continue;
            String port = ii.loadLabel(act).toString(), dev = cecName(ii), c = custom(ii);
            String name = c != null ? c : dev;
            return name != null ? name + "  ·  " + port : port;
        }
        return null;
    }

    /** Icone et texte de la ligne Reseau. Le nom du Wi-Fi demanderait la permission de localisation : on s'en passe. */
    String[] network() {
        try {
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return new String[]{"\ue648", t("Non connecté", "Not connected")};
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return new String[]{"\ue8be", "Ethernet"};
            if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                int lvl = WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 5);
                String[] q = fr ? new String[]{"très faible", "faible", "moyen", "bon", "excellent"} : new String[]{"very weak", "weak", "fair", "good", "excellent"};
                return new String[]{"\ue63e", t("Wi-Fi  ·  signal ", "Wi-Fi  ·  ") + q[Math.max(0, Math.min(4, lvl))] + (fr ? "" : " signal")};
            }
            return new String[]{"\ue63e", t("Connecté", "Connected")};
        } catch (Throwable e) { return new String[]{"\ue63e", t("Wi-Fi et Ethernet", "Wi-Fi and Ethernet")}; }
    }

    List<BluetoothDevice> bonded() {
        List<BluetoothDevice> l = new ArrayList<>();
        try { l.addAll(BluetoothAdapter.getDefaultAdapter().getBondedDevices()); } catch (Throwable ignored) { }
        // connectes d'abord
        Collections.sort(l, new java.util.Comparator<BluetoothDevice>() { public int compare(BluetoothDevice a, BluetoothDevice b) {
            return (connected(b) ? 1 : 0) - (connected(a) ? 1 : 0); } });
        return l;
    }

    String bluetoothText() {
        int n = 0, c = 0;
        for (BluetoothDevice d : bonded()) { n++; if (connected(d)) c++; }
        if (n == 0) return t("Associer un casque, une manette…", "Pair headphones, a gamepad…");
        return c == 0 ? t("Aucun appareil connecté", "No device connected")
                : c + (fr ? (c > 1 ? " appareils connectés" : " appareil connecté") : (c > 1 ? " devices connected" : " device connected"));
    }

    /** isConnected() est cachee mais accessible (liste grise) sur Android 11. */
    static boolean connected(BluetoothDevice d) {
        try { return (Boolean) d.getClass().getMethod("isConnected").invoke(d); } catch (Throwable e) { return false; }
    }

    String btName(BluetoothDevice d) {
        String n = null;
        try { if (Build.VERSION.SDK_INT >= 30) n = d.getAlias(); } catch (Throwable ignored) { }
        if (n == null || n.isEmpty()) n = d.getName();
        return n == null ? d.getAddress() : n;
    }

    /** Telecommande Bluetooth, d'apres son nom (la TCL_RC902V n'a pas de classe d'appareil). */
    static boolean isRemote(BluetoothDevice d) {
        String n = d.getName() == null ? "" : d.getName().toLowerCase(Locale.ROOT);
        return n.contains("remote") || n.contains("_rc") || n.startsWith("rc") || n.contains("télécommande");
    }

    /** Icone selon la classe de l'appareil. */
    static String btIcon(BluetoothDevice d) {
        BluetoothClass bc = d.getBluetoothClass();
        int major = bc == null ? BluetoothClass.Device.Major.UNCATEGORIZED : bc.getMajorDeviceClass();
        if (isRemote(d)) return "\ue8c7";
        switch (major) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                int dc = bc.getDeviceClass();
                return dc == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER || dc == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO ? "\ue32d" : "\uf01f";
            case BluetoothClass.Device.Major.COMPUTER: return "\ue31e";
            case BluetoothClass.Device.Major.PHONE: return "\ue32c";
            case BluetoothClass.Device.Major.PERIPHERAL:
                return (bc.getDeviceClass() & 0x40) != 0 ? "\ue312" : "\uea28";   // clavier, sinon manette
            default: return "\ue1a7";
        }
    }
}
