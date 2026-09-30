#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "lame.h"

JNIEXPORT jlong JNICALL
Java_com_oxxunov_voiceenhance_audioio_Mp3Native_create(JNIEnv *env, jclass c, jint sr, jint ch, jint kbps) {
    lame_t g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, sr);
    lame_set_out_samplerate(g, sr);
    lame_set_num_channels(g, ch);
    lame_set_mode(g, ch == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(g, vbr_off);
    lame_set_brate(g, kbps);
    lame_set_quality(g, 2); /* высокое качество кодирования */
    lame_set_bWriteVbrTag(g, 1);
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    return (jlong) (intptr_t) g;
}

static jbyteArray to_bytes(JNIEnv *env, unsigned char *buf, int n) {
    if (n < 0) return NULL;
    jbyteArray a = (*env)->NewByteArray(env, n);
    if (a && n > 0) (*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte *) buf);
    return a;
}

JNIEXPORT jbyteArray JNICALL
Java_com_oxxunov_voiceenhance_audioio_Mp3Native_encode(JNIEnv *env, jclass c, jlong h, jfloatArray left,
                                                       jfloatArray right, jint n) {
    lame_t g = (lame_t) (intptr_t) h;
    int cap = (int) (1.25 * n) + 7200;
    unsigned char *buf = (unsigned char *) malloc((size_t) cap);
    if (!buf) return NULL;
    jfloat *l = (*env)->GetFloatArrayElements(env, left, NULL);
    jfloat *r = right ? (*env)->GetFloatArrayElements(env, right, NULL) : l;
    int got = lame_encode_buffer_ieee_float(g, l, r, n, buf, cap);
    if (right) (*env)->ReleaseFloatArrayElements(env, right, r, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, left, l, JNI_ABORT);
    jbyteArray res = to_bytes(env, buf, got);
    free(buf);
    return res;
}

JNIEXPORT jbyteArray JNICALL
Java_com_oxxunov_voiceenhance_audioio_Mp3Native_flush(JNIEnv *env, jclass c, jlong h) {
    unsigned char buf[7200];
    int got = lame_encode_flush((lame_t) (intptr_t) h, buf, sizeof(buf));
    return to_bytes(env, buf, got);
}

JNIEXPORT jbyteArray JNICALL
Java_com_oxxunov_voiceenhance_audioio_Mp3Native_lameTag(JNIEnv *env, jclass c, jlong h) {
    unsigned char buf[4096];
    size_t n = lame_get_lametag_frame((lame_t) (intptr_t) h, buf, sizeof(buf));
    if (n > sizeof(buf)) n = 0;
    return to_bytes(env, buf, (int) n);
}

JNIEXPORT void JNICALL
Java_com_oxxunov_voiceenhance_audioio_Mp3Native_close(JNIEnv *env, jclass c, jlong h) {
    lame_close((lame_t) (intptr_t) h);
}
