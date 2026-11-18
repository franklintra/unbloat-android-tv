package tv.zenhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Ecran de l'AirPlay video : une SurfaceView centree sur fond noir, au format de la video, la plus grande
 * possible (bandes noires plutot qu'image deformee). Le decodeur et MediaPlayer etirent l'image a la taille
 * de la Surface : c'est donc la taille de la vue qui garde le format.
 * - recopie d'ecran : la Surface va au decodeur de Mirror.Video
 * - video des applis : un MediaPlayer lit la liste HLS servie par UxPlay sur localhost
 * Ouverte par Mirror (rallume la TV si elle dort), fermee a la fin de la session. Retour la ferme.
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {

    static final String TAG = Mirror.TAG;
    static MirrorActivity current;
    static int videoW, videoH;   // taille utile de la video en cours, 0 = inconnue

    FrameLayout root;

    SurfaceView view;
    SurfaceHolder holder;
    MediaPlayer player;
    boolean prepared;   // MediaPlayer pret : avant, getDuration/seekTo/start renvoient l'erreur -38
    final Handler h = new Handler();
    final Runnable poll = new Runnable() {
        public void run() {
            MediaPlayer p = player;
            if (p != null && prepared) {
                try {
                    Mirror.Hls.position = p.getCurrentPosition() / 1000.0;
                    int d = p.getDuration();
                    if (d > 0) Mirror.Hls.duration = d / 1000.0;
                } catch (Exception ignored) { }
            }
            h.postDelayed(this, 500);
        }
    };

    /** Video ou recopie en cours : l'ecran doit montrer l'image (AudioOnly ne l'eteint pas). */
    static volatile boolean picture;

    static void show(final Context c) {
        picture = true;
        // TV en mode Audio seul (musique AirPlay ou Spotify juste avant) : retroeclairage coupe, on le rallume.
        // Si une touche Retour a ete injectee, on attend qu'elle soit passee pour qu'elle ne ferme pas cette activite.
        long delay = AudioOnly.showPicture(c) ? 800 : 0;
        Mirror.main.postDelayed(new Runnable() {
            public void run() {
                if (!picture || current != null && !current.isFinishing()) return;
                c.startActivity(new Intent(c, MirrorActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            }
        }, delay);
    }

    static void close() {
        picture = false;
        Mirror.main.post(new Runnable() {
            public void run() { if (current != null) current.finish(); }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) fit();
            }
        });
        setContentView(root);
        view.getHolder().addCallback(this);
        h.post(poll);
    }

    /** Nouvelle taille de video (recopie : image decodee ; appli : MediaPlayer). Appelable de tout thread. */
    static void videoSize(final int w, final int h) {
        if (w <= 0 || h <= 0) return;
        Mirror.main.post(new Runnable() {
            public void run() {
                if (w == videoW && h == videoH) return;
                videoW = w; videoH = h;
                if (current != null) current.fit();
            }
        });
    }

    /** Plus grand rectangle au format de la video qui tient dans l'ecran, centre. */
    void fit() {
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return;
        int w = sw, h = sh;
        if (videoW > 0 && videoH > 0) {
            if ((long) videoW * sh > (long) videoH * sw) h = (int) Math.round((double) sw * videoH / videoW);   // bandes en haut et en bas
            else w = (int) Math.round((double) sh * videoW / videoH);                                         // bandes a gauche et a droite
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.width == w && lp.height == h) return;
        lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER;
        view.setLayoutParams(lp);
        Log.i(TAG, "video " + videoW + "x" + videoH + " affichee en " + w + "x" + h + " sur " + sw + "x" + sh);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        releasePlayer();
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        MediaPlayer p = player;
        if (p != null && prepared && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            try { if (p.isPlaying()) p.pause(); else p.start(); } catch (Exception ignored) { }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ------------------------------------------------------------------ Surface

    public void surfaceCreated(SurfaceHolder sh) {
        holder = sh;
        if (Mirror.Hls.url != null) startHls();
        else Mirror.Video.setSurface(sh.getSurface());
    }

    public void surfaceChanged(SurfaceHolder sh, int f, int w, int hgt) { }

    public void surfaceDestroyed(SurfaceHolder sh) {
        // l'ancienne instance (singleTask qui finit) ne coupe pas le decodeur de la nouvelle : surface statique
        Mirror.Video.clearSurface(sh.getSurface());
        releasePlayer();
        holder = null;
    }

    // ------------------------------------------------------------------ HLS

    void startHls() {
        final String url = Mirror.Hls.url;
        if (url == null || holder == null) return;
        Mirror.Video.setSurface(null);   // la Surface ne peut servir qu'a un producteur a la fois
        releasePlayer();
        final MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            p.setDisplay(holder);
            p.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(MediaPlayer mp, int w, int hgt) { if (player == mp) videoSize(w, hgt); }
            });
            p.setDataSource(url);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    if (player != mp) return;
                    prepared = true;
                    int d = mp.getDuration();
                    if (d > 0) Mirror.Hls.duration = d / 1000.0;
                    // surface recreee (ecran eteint puis rallume) : on reprend ou on en etait, pas au debut
                    double s = Mirror.Hls.position > 0 ? Mirror.Hls.position : Mirror.Hls.startAt;
                    if (s > 0) mp.seekTo((int) (s * 1000));
                    if (!Mirror.Hls.paused) mp.start();
                    Log.i(TAG, "HLS pret, duree " + d / 1000 + " s");
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { Mirror.Hls.finished = true; }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.w(TAG, "HLS erreur " + what + "/" + extra);
                    // -38 = appel dans le mauvais etat (avant onPrepared) : sans gravite. Le traiter comme une fin
                    // de video faisait arreter la lecture par l'iPhone des la premiere seconde.
                    if (what == -38) return true;
                    Mirror.Hls.finished = true;
                    return true;
                }
            });
            p.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "HLS", e);
            Mirror.Hls.finished = true;
        }
    }

    void stopHls() { releasePlayer(); }

    void seekHls(float pos) {
        MediaPlayer p = player;
        if (p == null) return;
        if (!prepared) { Mirror.Hls.startAt = pos; return; }   // applique par onPrepared
        try { p.seekTo((int) (pos * 1000)); } catch (Exception ignored) { }
    }

    void rateHls(float r) {
        MediaPlayer p = player;
        if (p == null) return;
        if (!prepared) { Mirror.Hls.paused = r == 0f; return; }   // applique par onPrepared
        try { if (r == 0f) p.pause(); else p.start(); } catch (Exception ignored) { }
    }

    void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        prepared = false;
        if (p != null) try { p.release(); } catch (Exception ignored) { }
    }
}
