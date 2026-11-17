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
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Ecran de l'AirPlay video : une SurfaceView plein ecran, sur fond noir.
 * - recopie d'ecran : la Surface va au decodeur de Mirror.Video
 * - video des applis : un MediaPlayer lit la liste HLS servie par UxPlay sur localhost
 * Ouverte par Mirror (rallume la TV si elle dort), fermee a la fin de la session. Retour la ferme.
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {

    static final String TAG = Mirror.TAG;
    static MirrorActivity current;

    SurfaceView view;
    SurfaceHolder holder;
    MediaPlayer player;
    final Handler h = new Handler();
    final Runnable poll = new Runnable() {
        public void run() {
            MediaPlayer p = player;
            if (p != null) {
                try {
                    Mirror.Hls.position = p.getCurrentPosition() / 1000.0;
                    int d = p.getDuration();
                    if (d > 0) Mirror.Hls.duration = d / 1000.0;
                } catch (Exception ignored) { }
            }
            h.postDelayed(this, 500);
        }
    };

    static void show(final Context c) {
        Mirror.main.post(new Runnable() {
            public void run() {
                if (current != null && !current.isFinishing()) return;
                c.startActivity(new Intent(c, MirrorActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            }
        });
    }

    static void close() {
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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        view.getHolder().addCallback(this);
        h.post(poll);
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
        if (p != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
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
        Mirror.Video.setSurface(null);
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
            p.setDataSource(url);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    if (player != mp) return;
                    int d = mp.getDuration();
                    if (d > 0) Mirror.Hls.duration = d / 1000.0;
                    float s = Mirror.Hls.startAt;
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
        if (p != null) try { p.seekTo((int) (pos * 1000)); } catch (Exception ignored) { }
    }

    void rateHls(float r) {
        MediaPlayer p = player;
        if (p == null) return;
        try { if (r == 0f) p.pause(); else p.start(); } catch (Exception ignored) { }
    }

    void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) try { p.release(); } catch (Exception ignored) { }
    }
}
