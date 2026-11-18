package tv.zenhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Night shift : du coucher du soleil a 1 h du matin. Sur le premier quart de la soiree, la TV chute vite :
 * retroeclairage du jour a 10, temperature de couleur TCL de 0 a -5 (son "chaud" d'usine). Ensuite elle continue
 * plus lentement jusqu'a 1 h : retroeclairage de 10 a 1, et la balance des blancs 2 points retire du bleu et
 * un peu de vert. Passe la moitie de la rampe, un second etage assombrit encore en ecrivant screen_brightness
 * (voir screenBrightness) : c'est ce qui passe sous le plancher du chemin graphic des applis.
 * Tout revient au jour au lever du soleil. Heures du soleil calculees sur la TV (formules NOAA) pour
 * Neuilly-sur-Seine, sans reseau.
 *
 * Les reglages passent par la fenetre TCL (com.tcl.settings/.ShowWindowService, uid systeme, la seule porte
 * qui marche sans root) : chaque changement ouvre le curseur TCL, referme aussitot. Ecran en mode "son seul",
 * la fenetre ne rallume pas l'ecran (verifie) : on envoie quand meme, rien ne se voit. En veille, rien n'est envoye.
 *
 * Le curseur Night shift de la barre des reglages prend la main jusqu'au prochain lever ou coucher du soleil.
 */
final class NightShift {
    static final double LAT = 48.885, LON = 2.268;   // Neuilly-sur-Seine
    static final int END_HOUR = 1;                   // niveau maximal a 1 h du matin
    static final int KNEE = 25;                      // fin de la chute rapide, en % de la rampe
    static final int WARMEST = -5;                   // curseur TCL : -5 chaud ... 0 normal ... +5 froid
    static final int KNEE_LIGHT = 10, NIGHT_LIGHT = 1;   // retroeclairage au quart de la rampe, puis a 1 h
    static final int BLUE_CUT = -30, GREEN_CUT = -15;    // gains 2 points a 1 h (curseurs TCL -30 ... +30)
    static final float FLOAT_FLOOR = 0.02f;          // plancher du HAL lights (0x0a) ; en dessous la TV borne
    static final String TCL_PKG = "com.tcl.settings", TCL_WINDOW = "com.tcl.settings.ShowWindowService";
    // ces curseurs sont caches dans le menu TCL : on les vise par le nom de leur classe, qui marche aussi
    static final String TCL_VM = "com.tcl.settings.viewmodel.picture.";
    static final String TWO_POINTS = TCL_VM + "TwoPointSelectLayoutVM",
            BLUE_GAIN = TCL_VM + "TwoPointsBSeekbarLayoutVM", GREEN_GAIN = TCL_VM + "TwoPointsGSeekbarLayoutVM";

    private NightShift() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("nightshift", Context.MODE_PRIVATE); }

    static int clamp(int v) { return Math.max(0, Math.min(100, v)); }

    /** Retroeclairage TCL le jour, 0 a 100 ; 100 par defaut. */
    static int day(Context c) { return prefs(c).getInt("dayLight", 100); }

    static void setDay(Context c, int v) { prefs(c).edit().putInt("dayLight", clamp(v)).apply(); }

    static boolean manual(Context c) { return prefs(c).getLong("manualUntil", 0) > System.currentTimeMillis(); }

    static void setManual(Context c, int v) {
        prefs(c).edit().putInt("manual", clamp(v)).putLong("manualUntil", nextChange(System.currentTimeMillis()))
                .remove("holdUntil").apply();
    }

    static void setAuto(Context c) { prefs(c).edit().remove("manualUntil").remove("holdUntil").apply(); }

    /**
     * Luminosite, mode image ou reglages d'image ouverts depuis la barre : la valeur choisie a la main tient jusqu'au
     * prochain lever ou coucher du soleil (comme le curseur Night shift), puis tout est renvoye. Sans cela le tic
     * suivant de la rampe l'ecrasait, et le cache sent* ignorait un mode image qui remet ses propres valeurs.
     */
    static void hold(Context c) {
        prefs(c).edit().putLong("holdUntil", nextChange(System.currentTimeMillis())).putBoolean("held", true).apply();
    }

    static boolean held(Context c) { return prefs(c).getLong("holdUntil", 0) > System.currentTimeMillis(); }

    /** Niveau en cours, 0 (jour) a 100 (nuit). */
    static int level(Context c) { return manual(c) ? prefs(c).getInt("manual", 0) : autoLevel(System.currentTimeMillis()); }

    // =====================================================================
    // Soleil
    /** Lever ou coucher du soleil a la date de day (heure locale), en millisecondes. */
    static long sun(Calendar day, boolean rise) {
        int n = day.get(Calendar.DAY_OF_YEAR);
        double g = 2 * Math.PI / 365 * (n - 1);
        double eqt = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double dec = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double lat = Math.toRadians(LAT);
        double ha = Math.toDegrees(Math.acos(Math.cos(Math.toRadians(90.833)) / (Math.cos(lat) * Math.cos(dec))
                - Math.tan(lat) * Math.tan(dec)));
        double min = 720 - 4 * (LON + (rise ? ha : -ha)) - eqt;   // minutes apres minuit UTC
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis() + Math.round(min * 60000);
    }

    /** Debut (coucher du soleil) et fin (1 h) de la rampe de la soiree en cours ou de la prochaine. */
    static long[] evening(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        if (now < sun(c, true)) c.add(Calendar.DAY_OF_MONTH, -1);   // apres minuit : la soiree a commence la veille
        long start = sun(c, false);
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, END_HOUR);
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return new long[]{start, Math.max(start, c.getTimeInMillis())};
    }

    /** Niveau automatique : 0 le jour, rampe du coucher du soleil a 1 h, 100 jusqu'au lever. */
    static int autoLevel(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        if (now >= sun(c, true) && now < sun(c, false)) return 0;
        long[] e = evening(now);
        if (e[1] <= e[0] || now >= e[1]) return 100;
        return clamp((int) Math.round(100.0 * (now - e[0]) / (e[1] - e[0])));
    }

    /** Prochain lever ou coucher du soleil : la fin d'un reglage manuel. */
    static long nextChange(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        for (int i = 0; i < 3; i++, c.add(Calendar.DAY_OF_MONTH, 1)) {
            long r = sun(c, true), s = sun(c, false);
            if (r > now) return r;
            if (s > now) return s;
        }
        return now + 12 * 3600000L;
    }

    // =====================================================================
    // Niveau -> reglages
    /** Avancee dans la chute lente, apres le premier quart : 0 a 1. */
    static float late(int p) { return p <= KNEE ? 0 : (p - KNEE) / (float) (100 - KNEE); }

    /** Temperature TCL : de 0 a -5 sur le premier quart, puis -5 ; la suite passe par les gains 2 points. */
    static int temperature(int p) { return Math.round(WARMEST * Math.min(p, KNEE) / (float) KNEE); }

    /**
     * Chute rapide du jour a 10 sur le premier quart de la rampe (pas de 10), puis lente jusqu'a 1 a 1 h (pas de 1) ;
     * jamais au-dessus du jour. Pas 0 : sur l'entree TV il vaut le point bas de la courbe d'usine, a peine allume.
     */
    static int backlight(int day, int p) {
        if (p <= 0) return day;
        int b = p < KNEE ? Math.max(KNEE_LIGHT, Math.round((KNEE_LIGHT + (day - KNEE_LIGHT) * (KNEE - p) / (float) KNEE) / 10f) * 10)
                : Math.round(KNEE_LIGHT - (KNEE_LIGHT - NIGHT_LIGHT) * late(p));
        return Math.min(day, b);
    }

    /** Gain 2 points : 0 jusqu'au quart, puis vers cut a 1 h, par pas de 5 pour limiter les fenetres TCL. */
    static int gain(int cut, int p) { return Math.round(cut * late(p) / 5f) * 5; }

    /**
     * Second etage, sous le curseur TCL : screen_brightness_float (1.0 = plein) alimente le HAL lights, dont
     * le plancher 0x0a est 2,6 fois plus bas que celui du chemin graphic des applis (26/255). L'entier
     * screen_brightness est ecarte expres : la fenetre TCL le resynchronise sur l'index du curseur a chaque
     * ouverture, donc il ne tient pas. Il ne sert qu'a partir de la moitie de la rampe.
     */
    static float screenBrightnessFloat(int p) {
        if (p <= 50) return 1f;
        return 1f - (1f - FLOAT_FLOOR) * (p - 50) / 50f;
    }

    /**
     * Ecrit screen_brightness_float si la valeur a change (aucune fenetre TCL ouverte, rien a refermer). Le jour
     * (1.0), on ne le remet que si c'est nous qui l'avions baisse (floatDimmed) : un autre reglage reste en place.
     */
    static void applyScreenBrightnessFloat(Context c, float value) {
        try {
            SharedPreferences sp = prefs(c);
            float now = Settings.System.getFloat(c.getContentResolver(), "screen_brightness_float", -1f);
            boolean day = value >= 1f;
            if (Math.abs(now - value) < 0.004f) {
                if (day && sp.getBoolean("floatDimmed", false)) sp.edit().remove("floatDimmed").apply();
                return;
            }
            if (day && !sp.getBoolean("floatDimmed", false)) return;
            Settings.System.putFloat(c.getContentResolver(), "screen_brightness_float", value);
            if (!day) sp.edit().putBoolean("floatDimmed", true).apply();
        } catch (Exception ignored) { }   // WRITE_SETTINGS non accorde : la seconde etage reste neutre
    }

    /** Applique le niveau en cours. Appele chaque minute par Keys, et par la barre des reglages. */
    static void apply(Context c) {
        int p = level(c);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;   // en veille
        if (held(c)) return;   // reglage d'image fait a la main (hold)
        if (prefs(c).getBoolean("held", false)) { prefs(c).edit().remove("held").apply(); resend(c); }
        send(c, "sentTwoPoints", 1, TWO_POINTS, tclText(c, "open", "Marche"));   // les gains n'agissent qu'avec 2 points
        send(c, "sentTemp", temperature(p), "picture_color_temperature", null);
        send(c, "sentBacklight", backlight(day(c), p), "picture_brightness", null);
        send(c, "sentBlue", gain(BLUE_CUT, p), BLUE_GAIN, null);
        send(c, "sentGreen", gain(GREEN_CUT, p), GREEN_GAIN, null);
        applyScreenBrightnessFloat(c, screenBrightnessFloat(p));
    }

    /** Envoie un reglage s'il a change depuis le dernier envoi ; text remplace la valeur pour une liste (Marche/Arret). */
    static void send(Context c, String key, int value, String item, String text) {
        SharedPreferences sp = prefs(c);
        if (sp.contains(key) && sp.getInt(key, 0) == value) return;
        lastSent = android.os.SystemClock.elapsedRealtime();
        if (tcl(c, item, text != null ? text : String.valueOf(value))) sp.edit().putInt(key, value).apply();
    }

    /** Dernier envoi a la fenetre TCL (elapsedRealtime) : TclWindows ne redemarre pas com.tcl.settings juste apres. */
    static volatile long lastSent = -60_000;

    /** Oublie ce qui a ete envoye : le prochain apply renvoie tout (changement d'entree, reglage touche a la main). */
    static void resend(Context c) {
        prefs(c).edit().remove("sentTwoPoints").remove("sentTemp").remove("sentBacklight")
                .remove("sentBlue").remove("sentGreen").apply();
    }

    /** Regle un curseur TCL (meme intent que l'assistant vocal TCL), puis referme sa fenetre. */
    static boolean tcl(Context c, String item, String value) {
        Intent set = new Intent("com.tcl.settings.SHOW_WINDOW").setClassName(TCL_PKG, TCL_WINDOW)
                .putExtra("Type", "Settings").putExtra("ITEM", item)
                .putExtra("MOTION", "SET").putExtra("MOTION_VALUE", value);
        try {
            if (c.startService(set) == null) return false;
            c.startService(new Intent("com.tcl.quickmenu.dismiss").setClassName(TCL_PKG, TCL_WINDOW));
            return true;
        } catch (Exception e) { return false; }
    }

    /** Texte d'une option TCL dans la langue de la TV : la fenetre compare le texte affiche ("Marche" en francais). */
    static String tclText(Context c, String name, String fallback) {
        try {
            Context t = c.createPackageContext(TCL_PKG, 0);
            int id = t.getResources().getIdentifier(name, "string", TCL_PKG);
            return id != 0 ? t.getString(id) : fallback;
        } catch (Exception e) { return fallback; }
    }
}
