/*
 * JNI-мост: libsamplerate (SINC_BEST) → DeepFilterNet3 (48 кГц, кадры по 480) → libsamplerate обратно.
 * Задержка модели компенсируется: первые (fft - hop) + lookahead*hop = 480 + 2*480 = 1440 сэмплов
 * на 48 кГц отбрасываются, в конце подаются нули — выход выровнен по времени с входом.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "samplerate.h"
#include "df_config.h"

#define TAG "VeNative"
#define DF_SR 48000
#define DF_DELAY 1440

typedef struct DFState DFState;
#ifdef DF_CREATE_HAS_LOG
extern DFState *df_create(const char *path, float atten_lim, const char *log_level);
#else
extern DFState *df_create(const char *path, float atten_lim);
#endif
extern uintptr_t df_get_frame_length(DFState *st);
extern void df_set_atten_lim(DFState *st, float lim_db);
extern float df_process_frame(DFState *st, float *input, float *output);
extern void df_free(DFState *model);

typedef struct {
    float *p;
    long len;
    long cap;
} Vec;

static float DUMMY[4];

static int vec_reserve(Vec *v, long extra) {
    if (v->len + extra <= v->cap) return 0;
    long nc = v->cap > 0 ? v->cap : 8192;
    while (nc < v->len + extra) nc *= 2;
    float *np = (float *) realloc(v->p, (size_t) nc * sizeof(float));
    if (!np) return -1;
    v->p = np;
    v->cap = nc;
    return 0;
}

typedef struct {
    DFState *df;
    int hop;
    int bypass;
    double up_ratio;
    double down_ratio;
    SRC_STATE *up;
    SRC_STATE *down;
    float *frame_in;
    float *frame_out;
    int fill;
    long drop;
    Vec x48;
    Vec y48;
    Vec out;
} Ve;

static int run_src(SRC_STATE *st, double ratio, const float *in, long n, int eoi, Vec *out) {
    long off = 0;
    for (;;) {
        long rem = n - off;
        long cap = (long) (rem * ratio) + 512;
        if (vec_reserve(out, cap)) return -1;
        SRC_DATA d;
        memset(&d, 0, sizeof(d));
        d.data_in = rem > 0 ? in + off : DUMMY;
        d.input_frames = rem;
        d.data_out = out->p + out->len;
        d.output_frames = cap;
        d.end_of_input = eoi;
        d.src_ratio = ratio;
        int err = src_process(st, &d);
        if (err) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "src_process: %s", src_strerror(err));
            return -1;
        }
        off += d.input_frames_used;
        out->len += d.output_frames_gen;
        if (eoi) {
            if (d.output_frames_gen == 0 && off >= n) break;
        } else {
            if (off >= n) break;
            if (d.input_frames_used == 0 && d.output_frames_gen == 0) break;
        }
    }
    return 0;
}

static int feed48(Ve *v, const float *x, long n) {
    for (long i = 0; i < n; i++) {
        v->frame_in[v->fill++] = x[i];
        if (v->fill == v->hop) {
            df_process_frame(v->df, v->frame_in, v->frame_out);
            v->fill = 0;
            long start = 0;
            if (v->drop > 0) {
                start = v->drop < v->hop ? v->drop : v->hop;
                v->drop -= start;
            }
            long m = v->hop - start;
            if (m > 0) {
                if (vec_reserve(&v->y48, m)) return -1;
                memcpy(v->y48.p + v->y48.len, v->frame_out + start, (size_t) m * sizeof(float));
                v->y48.len += m;
            }
        }
    }
    return 0;
}

static int stage(Ve *v, const float *in, long n, int eoi) {
    v->x48.len = 0;
    v->y48.len = 0;
    v->out.len = 0;
    const float *x = in;
    long xn = n;
    if (!v->bypass) {
        if (run_src(v->up, v->up_ratio, in, n, eoi, &v->x48)) return -1;
        x = v->x48.p;
        xn = v->x48.len;
    }
    if (xn > 0 && feed48(v, x, xn)) return -1;
    if (eoi) {
        // досчитываем хвост: неполный кадр + задержка модели
        long need = DF_DELAY + v->fill + v->hop;
        float *z = (float *) calloc((size_t) v->hop, sizeof(float));
        if (!z) return -1;
        long fed = 0;
        while (fed < need) {
            if (feed48(v, z, v->hop)) { free(z); return -1; }
            fed += v->hop;
        }
        free(z);
    }
    if (!v->bypass) {
        if (run_src(v->down, v->down_ratio, v->y48.p ? v->y48.p : DUMMY, v->y48.len, eoi, &v->out)) return -1;
    } else {
        if (vec_reserve(&v->out, v->y48.len)) return -1;
        if (v->y48.len > 0) memcpy(v->out.p, v->y48.p, (size_t) v->y48.len * sizeof(float));
        v->out.len = v->y48.len;
    }
    return 0;
}

static void ve_free(Ve *v) {
    if (!v) return;
    if (v->df) df_free(v->df);
    if (v->up) src_delete(v->up);
    if (v->down) src_delete(v->down);
    free(v->frame_in);
    free(v->frame_out);
    free(v->x48.p);
    free(v->y48.p);
    free(v->out.p);
    free(v);
}

static jfloatArray to_java(JNIEnv *env, Vec *out) {
    jfloatArray arr = (*env)->NewFloatArray(env, (jsize) out->len);
    if (arr && out->len > 0) (*env)->SetFloatArrayRegion(env, arr, 0, (jsize) out->len, out->p);
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_oxxunov_voiceenhance_ml_DeepFilterNative_create(JNIEnv *env, jclass clazz, jstring model_path,
                                                         jfloat atten_lim, jint sample_rate) {
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    Ve *v = (Ve *) calloc(1, sizeof(Ve));
    if (!v) {
        (*env)->ReleaseStringUTFChars(env, model_path, path);
        return 0;
    }
#ifdef DF_CREATE_HAS_LOG
    v->df = df_create(path, atten_lim, "error");
#else
    v->df = df_create(path, atten_lim);
#endif
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (!v->df) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "df_create failed");
        ve_free(v);
        return 0;
    }
    v->hop = (int) df_get_frame_length(v->df);
    v->frame_in = (float *) calloc((size_t) v->hop, sizeof(float));
    v->frame_out = (float *) calloc((size_t) v->hop, sizeof(float));
    v->drop = DF_DELAY;
    v->bypass = sample_rate == DF_SR;
    if (!v->bypass) {
        int err = 0;
        v->up = src_new(SRC_SINC_BEST_QUALITY, 1, &err);
        v->down = src_new(SRC_SINC_BEST_QUALITY, 1, &err);
        v->up_ratio = (double) DF_SR / (double) sample_rate;
        v->down_ratio = (double) sample_rate / (double) DF_SR;
    }
    if (!v->frame_in || !v->frame_out || (!v->bypass && (!v->up || !v->down))) {
        ve_free(v);
        return 0;
    }
    return (jlong) (intptr_t) v;
}

JNIEXPORT jint JNICALL
Java_com_oxxunov_voiceenhance_ml_DeepFilterNative_frameLength(JNIEnv *env, jclass clazz, jlong h) {
    return ((Ve *) (intptr_t) h)->hop;
}

JNIEXPORT void JNICALL
Java_com_oxxunov_voiceenhance_ml_DeepFilterNative_setAttenLim(JNIEnv *env, jclass clazz, jlong h, jfloat db) {
    df_set_atten_lim(((Ve *) (intptr_t) h)->df, db);
}

JNIEXPORT jfloatArray JNICALL
Java_com_oxxunov_voiceenhance_ml_DeepFilterNative_process(JNIEnv *env, jclass clazz, jlong h,
                                                          jfloatArray input, jint n) {
    Ve *v = (Ve *) (intptr_t) h;
    jfloat *in = (*env)->GetFloatArrayElements(env, input, NULL);
    int rc = stage(v, in, n, 0);
    (*env)->ReleaseFloatArrayElements(env, input, in, JNI_ABORT);
    if (rc) return NULL;
    return to_java(env, &v->out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_oxxunov_voiceenhance_ml_DeepFilterNative_flush(JNIEnv *env, jclass clazz, jlong h) {
    Ve *v = (Ve *) (intptr_t) h;
    if (stage(v, DUMMY, 0, 1)) return NULL;
    return to_java(env, &v->out);
}

JNIEXPORT void JNICALL
Java_com_oxxunov_voiceenhance_ml_DeepFilterNative_destroy(JNIEnv *env, jclass clazz, jlong h) {
    ve_free((Ve *) (intptr_t) h);
}

/* ---------- Отдельный многоканальный ресемплер (libsamplerate, SINC_BEST) ---------- */

typedef struct {
    SRC_STATE *st;
    double ratio;
    int ch;
    Vec out;
} Rs;

JNIEXPORT jlong JNICALL
Java_com_oxxunov_voiceenhance_ml_Resampler_nativeCreate(JNIEnv *env, jclass clazz, jint channels, jdouble ratio) {
    Rs *r = (Rs *) calloc(1, sizeof(Rs));
    if (!r) return 0;
    int err = 0;
    r->st = src_new(SRC_SINC_BEST_QUALITY, channels, &err);
    r->ratio = ratio;
    r->ch = channels;
    if (!r->st) {
        free(r);
        return 0;
    }
    return (jlong) (intptr_t) r;
}

JNIEXPORT jfloatArray JNICALL
Java_com_oxxunov_voiceenhance_ml_Resampler_nativeProcess(JNIEnv *env, jclass clazz, jlong h, jfloatArray input,
                                                          jint frames, jboolean eoi) {
    Rs *r = (Rs *) (intptr_t) h;
    jfloat *in = input ? (*env)->GetFloatArrayElements(env, input, NULL) : NULL;
    const float *src = in ? in : DUMMY;
    long off = 0;
    r->out.len = 0;
    int failed = 0;
    for (;;) {
        long rem = frames - off;
        long capFrames = (long) (rem * r->ratio) + 512;
        if (vec_reserve(&r->out, capFrames * r->ch)) { failed = 1; break; }
        SRC_DATA d;
        memset(&d, 0, sizeof(d));
        d.data_in = rem > 0 ? src + off * r->ch : DUMMY;
        d.input_frames = rem;
        d.data_out = r->out.p + r->out.len;
        d.output_frames = capFrames;
        d.end_of_input = eoi ? 1 : 0;
        d.src_ratio = r->ratio;
        int err = src_process(r->st, &d);
        if (err) { failed = 1; break; }
        off += d.input_frames_used;
        r->out.len += d.output_frames_gen * r->ch;
        if (eoi) {
            if (d.output_frames_gen == 0 && off >= frames) break;
        } else {
            if (off >= frames) break;
            if (d.input_frames_used == 0 && d.output_frames_gen == 0) break;
        }
    }
    if (in) (*env)->ReleaseFloatArrayElements(env, input, in, JNI_ABORT);
    if (failed) return NULL;
    return to_java(env, &r->out);
}

JNIEXPORT void JNICALL
Java_com_oxxunov_voiceenhance_ml_Resampler_nativeDestroy(JNIEnv *env, jclass clazz, jlong h) {
    Rs *r = (Rs *) (intptr_t) h;
    if (!r) return;
    if (r->st) src_delete(r->st);
    free(r->out.p);
    free(r);
}
