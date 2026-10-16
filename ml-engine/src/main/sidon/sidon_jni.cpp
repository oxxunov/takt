// JNI-мост к Sidon (CrispASR): 16 кГц моно → 48 кГц моно.
// Разбиение на куски (по самым тихим местам) и склейку делает Kotlin — так есть прогресс, отмена
// и файл любой длины не держится в памяти целиком.
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "sidon.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_oxxunov_voiceenhance_ml_SidonNative_init(JNIEnv *env, jclass, jstring path, jint threads, jint memBudgetMb) {
    // потолок памяти на один проход внимания (O(T^2)); куски у нас короче этого потолка
    std::string budget = std::to_string(memBudgetMb > 0 ? memBudgetMb : 640);
    setenv("CRISPASR_SIDON_MEM_BUDGET_MB", budget.c_str(), 1);
    const char *p = env->GetStringUTFChars(path, nullptr);
    sidon_context_params prm = sidon_context_default_params();
    prm.n_threads = threads > 0 ? threads : 4;
    prm.verbosity = 0;
    prm.use_gpu = false;
    sidon_context *ctx = sidon_init_from_file(p, prm);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_oxxunov_voiceenhance_ml_SidonNative_free(JNIEnv *, jclass, jlong h) {
    if (h) sidon_free(reinterpret_cast<sidon_context *>(h));
}

// Один точный проход по отрезку [offset, offset+len). Выход — 48 кГц, null при ошибке.
JNIEXPORT jfloatArray JNICALL
Java_com_oxxunov_voiceenhance_ml_SidonNative_restore(JNIEnv *env, jclass, jlong h, jfloatArray samples, jint offset, jint len) {
    if (!h || len <= 0) return nullptr;
    std::vector<float> in((size_t) len);
    env->GetFloatArrayRegion(samples, offset, len, in.data());
    if (env->ExceptionCheck()) return nullptr;
    std::vector<float> y = sidon_restore(reinterpret_cast<sidon_context *>(h), in.data(), len);
    if (y.empty()) return nullptr;
    jfloatArray out = env->NewFloatArray((jsize) y.size());
    if (out) env->SetFloatArrayRegion(out, 0, (jsize) y.size(), y.data());
    return out;
}

}
