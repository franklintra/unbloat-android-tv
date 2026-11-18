/*
 * JNI glue between UxPlay's AirPlay server library (lib/) and Zen Home (tv.zenhome.Mirror).
 *
 * UxPlay does the protocol: pairing, FairPlay (playfair), RTSP/HTTP, decryption of the mirror
 * stream (H.264 Annex-B) and of the audio (AAC-ELD, ALAC, AAC), and the HLS proxy for app video
 * (YouTube...). This file replaces uxplay.cpp: it forwards UxPlay's callbacks to static Java
 * methods, where MediaCodec, AudioTrack and MediaPlayer render them.
 *
 * Data buffers are passed as direct ByteBuffers over UxPlay's memory: Java must copy them before
 * returning. A supervisor thread restarts the HTTP server after a lost client, as uxplay.cpp's
 * main loop does (it must not run inside UxPlay's own threads).
 *
 * Licence: GPL-3.0 (links UxPlay).
 */

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/system_properties.h>

#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "stream.h"

#define TAG "ZenMirror"
#define MISSED_FEEDBACK_LIMIT 15

const char *dnssd_android_raop_name(dnssd_t *pub);

static JavaVM *vm;
static jclass cls;
static pthread_key_t attach_key;

static jmethodID m_log, m_conn, m_reset, m_client, m_vfmt, m_video, m_afmt, m_audio, m_volume,
        m_vreset, m_vpause, m_vresume, m_aflush, m_vflush, m_running,
        m_hplay, m_hscrub, m_hrate, m_hstop, m_hinfo, m_hremove;

static raop_t *raop;
static dnssd_t *dnssd;
static char password[64];
static char lang_system[64];   /* garde la chaine : airplay_video la lit a chaque video */
static bool hls;
static volatile double current_volume;
static volatile int open_connections, missed_feedback, reset_httpd;
static volatile bool supervising;
static pthread_t supervisor;

/* ------------------------------------------------------------------ JNI helpers */

static void detach(void *unused) { (void) unused; (*vm)->DetachCurrentThread(vm); }

static JNIEnv *jenv(void) {
    JNIEnv *e = NULL;
    if ((*vm)->GetEnv(vm, (void **) &e, JNI_VERSION_1_6) == JNI_OK) return e;
    if ((*vm)->AttachCurrentThread(vm, &e, NULL) != JNI_OK) return NULL;
    pthread_setspecific(attach_key, (void *) 1);   /* detach when the UxPlay thread exits */
    return e;
}

static void check(JNIEnv *e) {
    if ((*e)->ExceptionCheck(e)) { (*e)->ExceptionDescribe(e); (*e)->ExceptionClear(e); }
}

#define CALL_VOID(m, ...) do { JNIEnv *e = jenv(); if (e) { (*e)->CallStaticVoidMethod(e, cls, m, ##__VA_ARGS__); check(e); } } while (0)

static jstring jstr(JNIEnv *e, const char *s) { return s ? (*e)->NewStringUTF(e, s) : NULL; }

/* ------------------------------------------------------------------ UxPlay callbacks */

static void log_cb(void *c, int level, const char *msg) {
    (void) c;
    int p = level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level <= LOGGER_WARNING ? ANDROID_LOG_WARN
          : level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_print(p, TAG, "%s", msg);
}

static void conn_init(void *c) { (void) c; open_connections++; CALL_VOID(m_conn, (jint) open_connections); }

static void conn_destroy(void *c) {
    (void) c;
    if (open_connections > 0) open_connections--;
    if (!open_connections) missed_feedback = 0;
    CALL_VOID(m_conn, (jint) open_connections);
}

static void conn_feedback(void *c) { (void) c; missed_feedback = 0; }

static void conn_reset(void *c, int reason) {
    (void) c;
    __android_log_print(ANDROID_LOG_WARN, TAG, "conn_reset reason %d", reason);
    reset_httpd = 1;
    CALL_VOID(m_reset, (jint) reason);
}

static void report_client_request(void *c, char *deviceid, char *model, char *name, bool *admit) {
    (void) c;
    *admit = true;   /* access is controlled by the password */
    JNIEnv *e = jenv();
    if (!e) return;
    jstring a = jstr(e, deviceid), b = jstr(e, model), d = jstr(e, name);
    (*e)->CallStaticVoidMethod(e, cls, m_client, a, b, d);
    check(e);
    (*e)->DeleteLocalRef(e, a); (*e)->DeleteLocalRef(e, b); (*e)->DeleteLocalRef(e, d);
}

static int video_set_codec_and_size(void *c, video_codec_t codec, unsigned short ws, unsigned short hs,
                                    unsigned short x, unsigned short y, unsigned short w, unsigned short h) {
    (void) c; (void) x; (void) y;
    JNIEnv *e = jenv();
    if (!e) return 0;
    jint r = (*e)->CallStaticIntMethod(e, cls, m_vfmt, (jboolean) (codec == VIDEO_CODEC_H265),
                                       (jint) ws, (jint) hs, (jint) w, (jint) h);
    check(e);
    return r;
}

static void video_process(void *c, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) c; (void) ntp;
    JNIEnv *e = jenv();
    if (!e || data->data_len <= 0) return;
    jobject buf = (*e)->NewDirectByteBuffer(e, data->data, data->data_len);
    (*e)->CallStaticVoidMethod(e, cls, m_video, buf, (jlong) data->ntp_time_remote, (jlong) data->ntp_time_local,
                               (jint) data->nal_count, (jboolean) data->is_h265);
    check(e);
    (*e)->DeleteLocalRef(e, buf);
}

static void audio_get_format(void *c, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia,
                             uint64_t *audioFormat) {
    (void) c; (void) audioFormat;
    CALL_VOID(m_afmt, (jint) *ct, (jint) *spf, (jboolean) *usingScreen, (jboolean) *isMedia);
}

static void audio_process(void *c, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) c; (void) ntp;
    JNIEnv *e = jenv();
    if (!e || data->data_len <= 0) return;
    jobject buf = (*e)->NewDirectByteBuffer(e, data->data, data->data_len);
    (*e)->CallStaticVoidMethod(e, cls, m_audio, buf, (jint) data->ct, (jlong) data->ntp_time_remote,
                               (jlong) data->ntp_time_local, (jint) data->seqnum);
    check(e);
    (*e)->DeleteLocalRef(e, buf);
}

static void audio_flush(void *c) { (void) c; CALL_VOID(m_aflush); }
static void video_flush(void *c) { (void) c; CALL_VOID(m_vflush); }
static void video_pause(void *c) { (void) c; CALL_VOID(m_vpause); }
static void video_resume(void *c) { (void) c; CALL_VOID(m_vresume); }
static void mirror_video_running(void *c, bool running) { (void) c; CALL_VOID(m_running, (jboolean) running); }

static double audio_set_client_volume(void *c) { (void) c; return current_volume; }

static void audio_set_volume(void *c, float volume) {
    (void) c;
    current_volume = volume;
    CALL_VOID(m_volume, (jfloat) volume);
}

static void video_reset(void *c, reset_type_t type) {
    (void) c;
    switch (type) {
    case RESET_TYPE_NOHOLD:
        if (hls) raop_destroy_airplay_video(raop, -1);
        break;
    case RESET_TYPE_HLS_SHUTDOWN:
        if (hls) raop_destroy_airplay_video(raop, -1);
        raop_remove_hls_connections(raop);
        break;
    default:
        break;
    }
    CALL_VOID(m_vreset, (jint) type);
}

static const char *passwd(void *c, int *len) {
    (void) c;
    *len = (int) strlen(password);
    return *len ? password : NULL;
}

static void display_pin(void *c, char *pin) { (void) c; __android_log_print(ANDROID_LOG_INFO, TAG, "pin %s", pin); }
static void register_client(void *c, const char *id, const char *pk, const char *name) { (void) c; (void) id; (void) pk; (void) name; }
static bool check_register(void *c, const char *pk) { (void) c; (void) pk; return true; }
static void export_dacp(void *c, const char *ar, const char *id) { (void) c; (void) ar; (void) id; }
static void get_custom_profile(void *c, const char **p) { (void) c; *p = NULL; }
static void audio_set_metadata(void *c, const void *b, int l) { (void) c; (void) b; (void) l; }
static void audio_set_coverart(void *c, const void *b, int l) { (void) c; (void) b; (void) l; }
static void audio_stop_coverart_rendering(void *c) { (void) c; }
static void audio_set_progress(void *c, uint32_t *s, uint32_t *cu, uint32_t *en) { (void) c; (void) s; (void) cu; (void) en; }

static void on_video_play(void *c, const char *location, const float start) {
    (void) c;
    JNIEnv *e = jenv();
    if (!e) return;
    jstring l = jstr(e, location);
    (*e)->CallStaticVoidMethod(e, cls, m_hplay, l, (jfloat) start);
    check(e);
    (*e)->DeleteLocalRef(e, l);
}

static void on_video_scrub(void *c, const float position) { (void) c; CALL_VOID(m_hscrub, (jfloat) position); }
static void on_video_rate(void *c, const float rate) { (void) c; CALL_VOID(m_hrate, (jfloat) rate); }
static void on_video_stop(void *c) { (void) c; CALL_VOID(m_hstop); }

static float on_video_playlist_remove(void *c) {
    (void) c;
    JNIEnv *e = jenv();
    if (!e) return 0;
    jfloat r = (*e)->CallStaticFloatMethod(e, cls, m_hremove);
    check(e);
    return r;
}

/* Java returns {duration, position, seekStart, seekDuration, rate, stillPlaying} */
static void on_video_acquire_playback_info(void *c, playback_info_t *info) {
    (void) c;
    JNIEnv *e = jenv();
    if (!e) return;
    jdoubleArray a = (*e)->CallStaticObjectMethod(e, cls, m_hinfo);
    check(e);
    double v[6] = {0, 0, 0, 0, 0, 0};
    if (a && (*e)->GetArrayLength(e, a) >= 6) (*e)->GetDoubleArrayRegion(e, a, 0, 6, v);
    if (a) (*e)->DeleteLocalRef(e, a);
    info->duration = v[0];
    info->position = v[1];
    info->seek_start = v[2];
    info->seek_duration = v[3];
    info->rate = (float) v[4];
    info->ready_to_play = true;
    info->playback_likely_to_keep_up = true;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    if (v[5] == 0) { info->position = -1.0; info->duration = -1.0; }
}

/* ------------------------------------------------------------------ supervisor */

static void *supervise(void *unused) {
    (void) unused;
    while (supervising) {
        sleep(1);
        if (open_connections) {
            if (missed_feedback++ > MISSED_FEEDBACK_LIMIT) {
                __android_log_print(ANDROID_LOG_WARN, TAG, "client silent for %d s: reset", MISSED_FEEDBACK_LIMIT);
                reset_httpd = 1;
            }
        } else {
            missed_feedback = 0;
        }
        if (reset_httpd && raop) {
            reset_httpd = 0;
            missed_feedback = 0;
            unsigned short port = raop_get_port(raop);
            raop_stop_httpd(raop);
            raop_remove_known_connections(raop);
            if (raop_start_httpd(raop, &port) < 0) {
                /* sinon AirPlay reste annonce mais mort : on reessaie a la seconde suivante */
                __android_log_print(ANDROID_LOG_ERROR, TAG, "httpd restart failed on port %u, retrying", port);
                reset_httpd = 1;
                continue;
            }
            raop_set_port(raop, port);
            __android_log_print(ANDROID_LOG_INFO, TAG, "httpd restarted on port %u", port);
        }
    }
    return NULL;
}

/* ------------------------------------------------------------------ JNI entry points */

JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
    (void) reserved;
    vm = jvm;
    JNIEnv *e;
    if ((*vm)->GetEnv(vm, (void **) &e, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass c = (*e)->FindClass(e, "tv/zenhome/Mirror");
    if (!c) return -1;
    cls = (*e)->NewGlobalRef(e, c);
    pthread_key_create(&attach_key, detach);
#define M(var, name, sig) var = (*e)->GetStaticMethodID(e, cls, name, sig); if (!var) return -1;
    M(m_conn, "onConnections", "(I)V");
    M(m_reset, "onConnReset", "(I)V");
    M(m_client, "onClient", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    M(m_vfmt, "onVideoFormat", "(ZIIII)I");
    M(m_video, "onVideo", "(Ljava/nio/ByteBuffer;JJIZ)V");
    M(m_afmt, "onAudioFormat", "(IIZZ)V");
    M(m_audio, "onAudio", "(Ljava/nio/ByteBuffer;IJJI)V");
    M(m_volume, "onVolume", "(F)V");
    M(m_vreset, "onVideoReset", "(I)V");
    M(m_vpause, "onVideoPause", "()V");
    M(m_vresume, "onVideoResume", "()V");
    M(m_aflush, "onAudioFlush", "()V");
    M(m_vflush, "onVideoFlush", "()V");
    M(m_running, "onMirrorRunning", "(Z)V");
    M(m_hplay, "onHlsPlay", "(Ljava/lang/String;F)V");
    M(m_hscrub, "onHlsScrub", "(F)V");
    M(m_hrate, "onHlsRate", "(F)V");
    M(m_hstop, "onHlsStop", "()V");
    M(m_hinfo, "onHlsInfo", "()[D");
    M(m_hremove, "onHlsRemove", "()F");
#undef M
    (void) m_log;
    ntp_global_init();
    return JNI_VERSION_1_6;
}

/* returns the TCP port of the AirPlay server, or a negative error */
JNIEXPORT jint JNICALL Java_tv_zenhome_Mirror_nativeStart(JNIEnv *e, jclass c, jstring jname, jbyteArray jhw,
        jstring jkeyfile, jstring jpassword, jint width, jint height, jint fps, jboolean jhls, jboolean h265) {
    (void) c;
    if (raop) return -10;
    const char *name = (*e)->GetStringUTFChars(e, jname, NULL);
    const char *keyfile = (*e)->GetStringUTFChars(e, jkeyfile, NULL);
    const char *pw = jpassword ? (*e)->GetStringUTFChars(e, jpassword, NULL) : NULL;
    snprintf(password, sizeof(password), "%s", pw ? pw : "");
    if (pw) (*e)->ReleaseStringUTFChars(e, jpassword, pw);
    hls = jhls;

    char hw[6];
    (*e)->GetByteArrayRegion(e, jhw, 0, 6, (jbyte *) hw);
    char mac[18];
    snprintf(mac, sizeof(mac), "%02x:%02x:%02x:%02x:%02x:%02x", (unsigned char) hw[0], (unsigned char) hw[1],
             (unsigned char) hw[2], (unsigned char) hw[3], (unsigned char) hw[4], (unsigned char) hw[5]);

    int err = 0, ret = 0;
    dnssd = dnssd_init(name, (int) strlen(name), hw, 6, password[0] ? 2 : 0, &err);
    if (!dnssd || err) { ret = -1; goto out; }
    dnssd_set_airplay_features(dnssd, 0, hls);    /* AirPlay video */
    dnssd_set_airplay_features(dnssd, 4, hls);    /* HLS */
    dnssd_set_airplay_features(dnssd, 42, h265);  /* screen multi codec (H.265 mirroring) */
    dnssd_set_airplay_features(dnssd, 27, 0);     /* AirPlay 2 client pairing, not legacy */

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = conn_init;
    cbs.conn_destroy = conn_destroy;
    cbs.conn_reset = conn_reset;
    cbs.conn_feedback = conn_feedback;
    cbs.audio_process = audio_process;
    cbs.video_process = video_process;
    cbs.audio_flush = audio_flush;
    cbs.video_flush = video_flush;
    cbs.video_pause = video_pause;
    cbs.video_resume = video_resume;
    cbs.audio_set_client_volume = audio_set_client_volume;
    cbs.audio_set_volume = audio_set_volume;
    cbs.audio_get_format = audio_get_format;
    cbs.audio_set_metadata = audio_set_metadata;
    cbs.audio_set_coverart = audio_set_coverart;
    cbs.audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    cbs.audio_set_progress = audio_set_progress;
    cbs.report_client_request = report_client_request;
    cbs.display_pin = display_pin;
    cbs.register_client = register_client;
    cbs.check_register = check_register;
    cbs.passwd = passwd;
    cbs.export_dacp = export_dacp;
    cbs.video_reset = video_reset;
    cbs.video_set_codec_and_size = video_set_codec_and_size;
    cbs.get_custom_profile = get_custom_profile;
    cbs.mirror_video_running = mirror_video_running;
    cbs.on_video_play = on_video_play;
    cbs.on_video_scrub = on_video_scrub;
    cbs.on_video_rate = on_video_rate;
    cbs.on_video_stop = on_video_stop;
    cbs.on_video_playlist_remove = on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = on_video_acquire_playback_info;

    raop = raop_init(&cbs);
    if (!raop) { ret = -2; goto out; }
    raop_set_log_callback(raop, log_cb, NULL);
    raop_set_log_level(raop, LOGGER_INFO);
    if (raop_init2(raop, 1, mac, keyfile)) { free(raop); raop = NULL; ret = -3; goto out; }
    if (width) raop_set_plist(raop, "width", width);
    if (height) raop_set_plist(raop, "height", height);
    if (fps) { raop_set_plist(raop, "refreshRate", fps); raop_set_plist(raop, "maxFPS", fps); }
    if (hls) raop_set_plist(raop, "hls", 1);
    if (hls) {
        /* Langues de la TV pour l'AirPlay video (pistes audio et sous-titres HLS). UxPlay les lit dans LANGUAGE
           ou LANG, absentes sur Android : sans elles, lang_system reste NULL et la premiere liste maitre YouTube
           qui decrit ses langues fait planter select_master_playlist_language (strlen(NULL)). */
        char loc[PROP_VALUE_MAX] = "";
        __system_property_get("persist.sys.locale", loc);
        if (!loc[0]) __system_property_get("ro.product.locale", loc);
        char *dash = strchr(loc, '-');
        if (dash) snprintf(lang_system, sizeof(lang_system), "%s:%.*s:en", loc, (int) (dash - loc), loc);
        else snprintf(lang_system, sizeof(lang_system), "%s%sen", loc, loc[0] ? ":" : "");
        raop_set_lang(raop, NULL, NULL, lang_system);
        __android_log_print(ANDROID_LOG_INFO, TAG, "langues HLS : %s", lang_system);
    }

    unsigned short tcp[3] = {0, 7000, 0}, udp[3] = {0, 0, 0};   /* {mirror data (dynamic), AirPlay} */
    raop_set_tcp_ports(raop, tcp);
    raop_set_udp_ports(raop, udp);
    unsigned short port = raop_get_port(raop);
    if (raop_start_httpd(raop, &port) < 0) { raop_destroy(raop); raop = NULL; ret = -4; goto out; }
    raop_set_port(raop, port);
    raop_set_dnssd(raop, dnssd);
    dnssd_register_raop(dnssd, port);
    dnssd_register_airplay(dnssd, port);

    supervising = true;
    pthread_create(&supervisor, NULL, supervise, NULL);
    ret = port;
out:
    (*e)->ReleaseStringUTFChars(e, jname, name);
    (*e)->ReleaseStringUTFChars(e, jkeyfile, keyfile);
    if (ret < 0 && dnssd) { dnssd_destroy(dnssd); dnssd = NULL; }
    return ret;
}

JNIEXPORT void JNICALL Java_tv_zenhome_Mirror_nativeStop(JNIEnv *e, jclass c) {
    (void) e; (void) c;
    if (supervising) { supervising = false; pthread_join(supervisor, NULL); }
    if (raop) { raop_destroy(raop); raop = NULL; }
    if (dnssd) { dnssd_destroy(dnssd); dnssd = NULL; }
    open_connections = 0;
}

static jbyteArray bytes(JNIEnv *e, const char *p, int len) {
    jbyteArray a = (*e)->NewByteArray(e, len);
    (*e)->SetByteArrayRegion(e, a, 0, len, (const jbyte *) p);
    return a;
}

/* TXT records in DNS wire format: 0 = _raop._tcp, 1 = _airplay._tcp */
JNIEXPORT jbyteArray JNICALL Java_tv_zenhome_Mirror_nativeTxt(JNIEnv *e, jclass c, jint service) {
    (void) c;
    if (!dnssd) return NULL;
    int len = 0;
    const char *p = service ? dnssd_get_airplay_txt(dnssd, &len) : dnssd_get_raop_txt(dnssd, &len);
    return bytes(e, p, len);
}

JNIEXPORT jstring JNICALL Java_tv_zenhome_Mirror_nativeRaopName(JNIEnv *e, jclass c) {
    (void) c;
    return dnssd ? (*e)->NewStringUTF(e, dnssd_android_raop_name(dnssd)) : NULL;
}
