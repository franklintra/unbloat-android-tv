package tv.zenhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Recepteur AirPlay video : recopie d'ecran (iPhone, iPad, Mac) et video des applis (YouTube, Photos...).
 *
 * Le protocole vient d'UxPlay (github.com/FDH2/UxPlay, GPL-3), compile pour la TV dans libzenairplay.so
 * (dossier native/) : appairage AirPlay 2, FairPlay ("playfair"), dechiffrement du flux, proxy HLS. Ce fichier
 * recoit ses rappels (native/zenairplay.c) et fait le rendu :
 * - recopie : H.264 Annex-B -> MediaCodec (decodeur materiel Realtek) -> Surface de MirrorActivity
 * - son : AAC-ELD (recopie), AAC-LC -> MediaCodec, ALAC -> Alac.java, puis AudioTrack
 * - video des applis : UxPlay sert une liste HLS sur localhost -> MediaPlayer dans MirrorActivity
 *
 * Annonce Bonjour _airplay._tcp par NsdManager, avec les TXT construits par UxPlay (native/dnssd_android.c).
 * Meme nom et meme adresse materielle que l'enceinte AirPlay.java (_raop._tcp, port 5000) : iOS ne montre qu'un
 * appareil "Chambre Franklin". Le son seul va a AirPlay.java, la video et la recopie ici (port 7000).
 * Meme code que l'enceinte (pref airplay/password, 1234).
 */
final class Mirror {

    static final String TAG = "ZenMirror";
    static final int TYPE_NOHOLD = 0, TYPE_RTP_SHUTDOWN = 1, TYPE_HLS_SHUTDOWN = 2, TYPE_HLS_EOS = 3,
            TYPE_ON_VIDEO_PLAY = 4, TYPE_RTP_TO_HLS = 5;

    static native int nativeStart(String name, byte[] hw, String keyfile, String password, int width, int height,
                                  int fps, boolean hls, boolean h265);
    static native void nativeStop();
    static native byte[] nativeTxt(int service);
    static native String nativeRaopName();

    static Context ctx;
    static String name;
    static int port;
    static NsdManager nsd;
    static NsdManager.RegistrationListener reg;
    static PowerManager.WakeLock connLock;
    static final Handler main = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ cycle de vie

    static synchronized void start(Context c) {
        if (port > 0) { reannounce(); return; }
        ctx = c.getApplicationContext();
        try {
            System.loadLibrary("zenairplay");
        } catch (Throwable t) {
            Log.e(TAG, "libzenairplay.so absente", t);
            return;
        }
        SharedPreferences p = ctx.getSharedPreferences("airplay", Context.MODE_PRIVATE);
        // meme adresse et meme nom que l'enceinte AirPlay.java : iOS fusionne _raop et _airplay en un seul appareil
        String h = AirPlay.hwPref(p);
        name = AirPlay.namePref(p);
        String pw = p.getString("password", "1234");
        String key = new File(ctx.getFilesDir(), "airplay-mirror.pem").getPath();   // cle d'appairage gardee
        port = nativeStart(name, AirPlay.unhex(h), key, pw, 1920, 1080, 30, true, false);
        if (port <= 0) { Log.e(TAG, "serveur AirPlay video : erreur " + port); port = 0; return; }
        Log.i(TAG, "AirPlay video sur le port " + port);
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        connLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zenhome:mirror-conn");
        connLock.setReferenceCounted(false);
        register();
    }

    static synchronized void stop() {
        unregister();
        if (port > 0) { nativeStop(); port = 0; }
        Video.release();
        Audio.stop();
    }

    static synchronized void reannounce() { unregister(); register(); }
    static synchronized void reannounceIfRunning() { if (port > 0) reannounce(); }

    static synchronized void register() {
        if (port <= 0 || reg != null) return;
        if (nsd == null) nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo si = new NsdServiceInfo();
        si.setServiceName(name);
        si.setServiceType("_airplay._tcp");
        si.setPort(port);
        byte[] txt = nativeTxt(1);
        for (int i = 0; txt != null && i < txt.length; ) {
            int n = txt[i++] & 0xff;
            String kv = new String(txt, i, Math.min(n, txt.length - i), java.nio.charset.StandardCharsets.UTF_8);
            i += n;
            int eq = kv.indexOf('=');
            if (eq > 0) si.setAttribute(kv.substring(0, eq), kv.substring(eq + 1));
        }
        reg = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "annonce : " + i.getServiceName()); }
            public void onRegistrationFailed(NsdServiceInfo i, int err) { Log.w(TAG, "annonce refusee " + err); synchronized (Mirror.class) { reg = null; } }
            public void onServiceUnregistered(NsdServiceInfo i) { }
            public void onUnregistrationFailed(NsdServiceInfo i, int err) { }
        };
        try { nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, reg); }
        catch (Exception e) { Log.w(TAG, "annonce", e); reg = null; }
    }

    static synchronized void unregister() {
        if (reg == null) return;
        try { nsd.unregisterService(reg); } catch (Exception ignored) { }
        reg = null;
    }

    // ------------------------------------------------------------------ rappels d'UxPlay (threads natifs)

    static void onConnections(int n) {
        Log.i(TAG, n + " connexion(s)");
        if (n > 0) connLock.acquire(10 * 60_000L);   // la TV ne se rendort pas pendant la session
        else {
            if (connLock.isHeld()) connLock.release();
            Video.release();
            Audio.stop();
            Hls.stop();
            MirrorActivity.close();
        }
    }

    static void onConnReset(int reason) { Log.w(TAG, "connexion perdue (" + reason + ")"); }

    static void onClient(String id, String model, String client) {
        Log.i(TAG, "client " + client + " (" + model + ") " + id);
        connLock.acquire(60_000L);
    }

    static int onVideoFormat(boolean h265, int ws, int hs, int w, int h) {
        Log.i(TAG, "recopie " + (h265 ? "H.265 " : "H.264 ") + w + "x" + h + " (source " + ws + "x" + hs + ")");
        Hls.stop();
        Video.format(h265, w, h);
        MirrorActivity.videoSize(w, h);
        MirrorActivity.show(ctx);
        return 0;
    }

    static void onVideo(ByteBuffer b, long ntpRemote, long ntpLocal, int nals, boolean h265) {
        Video.feed(b, ntpRemote / 1000);
    }

    static void onMirrorRunning(boolean running) { Log.i(TAG, "recopie " + (running ? "en cours" : "arretee")); }

    static void onVideoReset(int type) {
        Log.i(TAG, "video_reset " + type);
        switch (type) {
            case TYPE_RTP_SHUTDOWN:
            case TYPE_RTP_TO_HLS:
                Video.release();
                if (type == TYPE_RTP_SHUTDOWN && Hls.url == null) MirrorActivity.close();
                break;
            case TYPE_HLS_SHUTDOWN:
            case TYPE_HLS_EOS:
            case TYPE_NOHOLD:
                Video.release();
                Hls.stop();
                MirrorActivity.close();
                break;
            default:
                break;
        }
    }

    static void onVideoPause() { }
    static void onVideoResume() { }
    static void onVideoFlush() { }

    static void onAudioFormat(int ct, int spf, boolean usingScreen, boolean isMedia) {
        Log.i(TAG, "son ct=" + ct + " spf=" + spf + (usingScreen ? " recopie" : "") + (isMedia ? " media" : ""));
        Audio.start(ct, spf);
    }

    static void onAudio(ByteBuffer b, int ct, long ntpRemote, long ntpLocal, int seq) { Audio.feed(b, ct); }
    static void onAudioFlush() { Audio.flush(); }

    static void onVolume(float db) { AirPlay.tvVolume(ctx, db); }

    // video des applis (HLS)
    static void onHlsPlay(String url, float start) {
        Log.i(TAG, "video HLS " + url + " a " + start + " s");
        Video.release();
        Hls.play(url, start);
        MirrorActivity.show(ctx);
    }
    static void onHlsScrub(float pos) { Hls.seek(pos); }
    static void onHlsRate(float rate) { Hls.rate(rate); }
    static void onHlsStop() { Hls.stop(); MirrorActivity.close(); }
    static double[] onHlsInfo() { return Hls.info(); }
    static float onHlsRemove() { Hls.rate(0); return (float) Hls.position; }

    // ------------------------------------------------------------------ recopie d'ecran

    /**
     * Decodeur video. Les trames arrivent avant que la Surface existe (l'activite s'ouvre) : on garde le GOP
     * en cours (depuis le dernier SPS) et on le rejoue au demarrage du decodeur. iOS n'envoie d'image cle
     * qu'au debut et aux changements de taille : sans ce tampon, l'image resterait grise.
     */
    static final class Video {
        static final Object lock = new Object();
        static final int GOP_MAX = 600;
        static String mime = MediaFormat.MIMETYPE_VIDEO_AVC;
        static int width = 1920, height = 1080;
        static Surface surface;
        static MediaCodec codec;
        static Thread drain;
        static final ArrayList<byte[]> gop = new ArrayList<>();
        static final ArrayList<Long> gopPts = new ArrayList<>();
        static boolean needKey = true;

        static void format(boolean h265, int w, int h) {
            synchronized (lock) {
                mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
                if (w > 0 && h > 0) { width = w; height = h; }
                gop.clear(); gopPts.clear();
                if (surface != null) restart();
            }
        }

        static void setSurface(Surface s) {
            synchronized (lock) {
                surface = s;
                if (s == null) releaseCodec(); else restart();
            }
        }

        static void release() {
            synchronized (lock) { releaseCodec(); gop.clear(); gopPts.clear(); }
        }

        static void restart() {
            releaseCodec();
            try {
                MediaCodec c = MediaCodec.createDecoderByType(mime);
                MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 << 20);
                if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                c.configure(f, surface, null, 0);
                c.start();
                codec = c;
                needKey = true;
                Log.i(TAG, "decodeur " + c.getName() + " " + width + "x" + height + ", " + gop.size() + " trames en attente");
                final MediaCodec dc = c;
                drain = new Thread(new Runnable() { public void run() { drainLoop(dc); } }, "mirror-video-out");
                drain.setDaemon(true);
                drain.start();
                for (int i = 0; i < gop.size(); i++) queue(gop.get(i), gopPts.get(i), i == 0);
            } catch (Exception e) {
                Log.e(TAG, "decodeur video", e);
                codec = null;
            }
        }

        static void releaseCodec() {
            MediaCodec c = codec;
            codec = null;
            if (c == null) return;
            try { c.stop(); } catch (Exception ignored) { }
            try { c.release(); } catch (Exception ignored) { }
            Thread t = drain; drain = null;
            if (t != null) try { t.join(500); } catch (InterruptedException ignored) { }
        }

        static void drainLoop(MediaCodec c) {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (codec == c) {
                try {
                    int o = c.dequeueOutputBuffer(bi, 20_000);
                    if (o >= 0) c.releaseOutputBuffer(o, true);   // affichage immediat : latence minimale
                    else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat(c.getOutputFormat());
                } catch (Exception e) { return; }
            }
        }

        /** Taille utile de l'image decodee (sans les marges d'alignement) : l'ecran garde ce format. */
        static void outputFormat(MediaFormat f) {
            int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
            if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
            if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
            Log.i(TAG, "image decodee " + w + "x" + h);
            MirrorActivity.videoSize(w, h);
        }

        /** NAL de parametres en tete du paquet : SPS (H.264 type 7) ou VPS (H.265 type 32). */
        static boolean isKey(ByteBuffer b) {
            int n = b.limit();
            for (int i = 0; i + 4 < n; i++) {
                if (b.get(i) == 0 && b.get(i + 1) == 0 && b.get(i + 2) == 0 && b.get(i + 3) == 1) {
                    int h = b.get(i + 4) & 0xff;
                    if (mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? ((h >> 1) & 0x3f) == 32 : (h & 0x1f) == 7) return true;
                    return false;   // seul le premier NAL compte
                }
            }
            return false;
        }

        static void feed(ByteBuffer b, long ptsUs) {
            byte[] data = new byte[b.remaining()];
            b.get(data);
            synchronized (lock) {
                boolean key = isKey(ByteBuffer.wrap(data));
                if (key) { gop.clear(); gopPts.clear(); }
                if (key || (!gop.isEmpty() && gop.size() < GOP_MAX)) { gop.add(data); gopPts.add(ptsUs); }
                if (codec != null) queue(data, ptsUs, key);
            }
        }

        static void queue(byte[] data, long ptsUs, boolean key) {
            if (needKey && !key) return;
            needKey = false;
            try {
                int i = codec.dequeueInputBuffer(50_000);
                if (i < 0) { Log.w(TAG, "decodeur plein, trame perdue"); return; }
                ByteBuffer in = codec.getInputBuffer(i);
                in.clear();
                if (data.length > in.capacity()) { codec.queueInputBuffer(i, 0, 0, ptsUs, 0); return; }
                in.put(data);
                codec.queueInputBuffer(i, 0, data.length, ptsUs, 0);
            } catch (Exception e) {
                Log.w(TAG, "trame video", e);
            }
        }
    }

    // ------------------------------------------------------------------ son

    static final class Audio {
        static final int RATE = 44100;
        static int ct = -1, spf = 352;
        static AudioTrack track;
        static MediaCodec aac;
        static Alac alac;
        static short[] pcm = new short[4096];
        static final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

        static synchronized void start(int type, int samplesPerFrame) {
            stop();
            ct = type;
            spf = samplesPerFrame > 0 ? samplesPerFrame : (type == 2 ? 352 : type == 8 ? 480 : 1024);
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 16384), AudioTrack.MODE_STREAM);
            track.play();
            try {
                if (type == 2) {
                    alac = new Alac(spf, 16, 40, 10, 14, 2, 255);
                } else if (type == 4 || type == 8) {
                    MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
                    byte[] csd = type == 8 ? new byte[]{(byte) 0xf8, (byte) 0xe8, 0x50, 0x00} : new byte[]{0x12, 0x10};
                    f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                    f.setInteger(MediaFormat.KEY_AAC_PROFILE, type == 8 ? 39 : 2);   // 39 = AAC-ELD
                    f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
                    aac = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    aac.configure(f, null, null, 0);
                    aac.start();
                    Log.i(TAG, "decodeur son " + aac.getName());
                }
            } catch (Exception e) {
                Log.e(TAG, "decodeur son", e);
            }
        }

        static synchronized void stop() {
            if (aac != null) { try { aac.stop(); aac.release(); } catch (Exception ignored) { } aac = null; }
            if (track != null) { try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) { } track = null; }
            alac = null;
            ct = -1;
        }

        static synchronized void flush() {
            if (track != null) { try { track.pause(); track.flush(); track.play(); } catch (Exception ignored) { } }
            if (aac != null) { try { aac.flush(); } catch (Exception ignored) { } }
        }

        static synchronized void feed(ByteBuffer b, int type) {
            if (track == null) return;
            if (type != ct) start(type, 0);
            int len = b.remaining();
            try {
                if (type == 2 && alac != null) {
                    byte[] in = new byte[len];
                    b.get(in);
                    if (pcm.length < spf * 2) pcm = new short[spf * 2];
                    int n = alac.decode(in, 0, len, pcm);
                    if (n > 0) track.write(pcm, 0, n * 2);
                } else if (aac != null) {
                    int i = aac.dequeueInputBuffer(10_000);
                    if (i >= 0) {
                        ByteBuffer in = aac.getInputBuffer(i);
                        in.clear();
                        in.put(b);
                        aac.queueInputBuffer(i, 0, len, 0, 0);
                    }
                    int o;
                    while ((o = aac.dequeueOutputBuffer(bi, 0)) >= 0) {
                        ByteBuffer out = aac.getOutputBuffer(o);
                        int samples = bi.size / 2;
                        if (pcm.length < samples) pcm = new short[samples];
                        out.position(bi.offset);
                        out.asShortBuffer().get(pcm, 0, samples);
                        aac.releaseOutputBuffer(o, false);
                        track.write(pcm, 0, samples);
                    }
                } else if (type == 0 || type == 1) {   // PCM 16 bits big-endian
                    if (pcm.length < len / 2) pcm = new short[len / 2];
                    for (int k = 0; k < len / 2; k++) pcm[k] = (short) (((b.get() & 0xff) << 8) | (b.get() & 0xff));
                    track.write(pcm, 0, len / 2);
                }
            } catch (Exception e) {
                Log.w(TAG, "trame son", e);
            }
        }
    }

    // ------------------------------------------------------------------ video des applis (HLS)

    /** Etat partage avec MirrorActivity, qui tient le MediaPlayer (il lui faut la Surface). */
    static final class Hls {
        static volatile String url;
        static volatile float startAt;
        static volatile double duration, position;
        static volatile boolean playing, finished, paused;

        static void play(String u, float start) {
            url = u; startAt = start; duration = 0; position = start; playing = true; finished = false; paused = false;
            main.post(new Runnable() { public void run() { MirrorActivity a = MirrorActivity.current; if (a != null) a.startHls(); } });
        }

        static void stop() {
            if (url == null) return;
            url = null; playing = false;
            main.post(new Runnable() { public void run() { MirrorActivity a = MirrorActivity.current; if (a != null) a.stopHls(); } });
        }

        static void seek(final float pos) {
            position = pos;
            main.post(new Runnable() { public void run() { MirrorActivity a = MirrorActivity.current; if (a != null) a.seekHls(pos); } });
        }

        static void rate(final float r) {
            paused = r == 0f;
            main.post(new Runnable() { public void run() { MirrorActivity a = MirrorActivity.current; if (a != null) a.rateHls(r); } });
        }

        /** {duree, position, debut recherche, duree recherche, vitesse, encore en cours} pour /playback-info. */
        static double[] info() {
            double d = duration;
            return new double[]{d, position, 0, d, paused ? 0 : 1, finished ? 0 : 1};
        }
    }

    static String describe() { return String.format(Locale.ROOT, "%s port %d", name, port); }
}
