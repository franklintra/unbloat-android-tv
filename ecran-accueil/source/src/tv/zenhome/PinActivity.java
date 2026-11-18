package tv.zenhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Code d'appairage de la telecommande de l'iPhone (Companion.java), en grand au milieu de l'ecran, par-dessus
 * l'appli en cours. Ouverte par RemoteActions.showPin, fermee par RemoteActions.hidePin (appairage reussi,
 * refuse ou abandonne). TV en veille : allume l'ecran, comme WakeActivity.
 */
public class PinActivity extends Activity {

    static final String EXTRA_PIN = "pin";
    static PinActivity self;
    static volatile String wanted;   // code a montrer, null apres hidePin (meme si l'activite n'est pas encore creee)
    TextView code;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        self = this;

        float d = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) / 1280f;
        Typeface reg = font("Poppins-Regular.ttf"), med = font("Poppins-Medium.ttf");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x8C000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Math.round(40 * d);
        card.setPadding(p, Math.round(32 * d), p, Math.round(36 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsPanel.BG);
        bg.setCornerRadius(24 * d);
        card.setBackground(bg);

        TextView title = text("Code pour l'iPhone", 22 * d, med, SettingsPanel.TXT);
        card.addView(title);
        code = text("", 96 * d, med, SettingsPanel.TXT);
        code.setLetterSpacing(0.25f);
        code.setPadding(Math.round(24 * d * 0.25f), Math.round(12 * d), 0, Math.round(8 * d));
        card.addView(code);
        card.addView(text("Tapez-le dans la télécommande de l'iPhone", 15 * d, reg, SettingsPanel.SUB));

        root.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        show(i);
    }

    void show(Intent i) {
        String pin = i != null ? i.getStringExtra(EXTRA_PIN) : null;
        if (pin == null || !pin.equals(wanted)) { finish(); return; }
        code.setText(pin);
    }

    TextView text(String s, float px, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    Typeface font(String f) { try { return Typeface.createFromAsset(getAssets(), f); } catch (Exception e) { return null; } }

    /** RemoteActions.hidePin, fil principal. */
    static void close() {
        wanted = null;
        PinActivity a = self;
        if (a != null) a.finish();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (self == this) self = null;
        super.onDestroy();
    }
}
