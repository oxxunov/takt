#include <jni.h>
#include <android/log.h>
#include <vector>

#include "analyzer.h"
#include "click_engine.h"
#include "crash_handler.h"

#define LOG_TAG "takt-native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

namespace {

/** Лог анализатора уходит в logcat: тег takt-native. */
void analyzerLog(const char* message) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", message);
}

constexpr const char* kAnalyzerClass =
        "com/tupicgames/takt/engine/analysis/NativeAnalyzer";
constexpr const char* kClickClass =
        "com/tupicgames/takt/engine/audio/NativeClickEngine";
constexpr const char* kDiagClass =
        "com/tupicgames/takt/engine/analysis/NativeDiagnostics";

// Полей на онсет — держать синхронно с NativeAnalyzer.FIELDS.
constexpr int kFields = 7;      // time, strength, low, mid, high, centroid, sustain
constexpr int kBeatFields = 5;  // time, confidence, strength, barIndex, positionInBar
constexpr int kHeader = 10;     // + размер такта и разброс темпа

// -------------------------------------------------------------- анализ ----

jfloatArray analyze(JNIEnv* env, jobject, jfloatArray samples, jint sampleRate,
                    jfloat minOnsetGap, jfloat thresholdK) {
    jsize count = env->GetArrayLength(samples);
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return env->NewFloatArray(0);

    rg::setLogSink(analyzerLog);

    rg::AnalyzerConfig cfg;
    if (minOnsetGap > 0.f) cfg.minOnsetGap = minOnsetGap;
    if (thresholdK > 0.f)  cfg.thresholdK = thresholdK;

    rg::AnalysisResult res = rg::analyze(data, size_t(count), int(sampleRate), cfg);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    LOGI("analyze: %.1fs bpm=%.2f conf=%.2f meter=%d spread=%.1f onsets=%zu beats=%zu",
         res.durationSec, res.bpm, res.confidence, res.meter, res.tempoSpread,
         res.onsets.size(), res.beats.size());

    const jsize total = kHeader
                      + jsize(res.onsets.size()) * kFields
                      + jsize(res.beats.size()) * kBeatFields
                      + jsize(res.envelope.size());
    std::vector<float> flat(static_cast<size_t>(total), 0.f);
    flat[0] = res.bpm;
    flat[1] = res.firstBeatSec;
    flat[2] = res.durationSec;
    flat[3] = res.confidence;
    flat[4] = float(res.onsets.size());
    flat[5] = float(res.beats.size());
    flat[6] = float(res.envelope.size());
    flat[7] = res.envelopeHz;
    flat[8] = float(res.meter);
    flat[9] = res.tempoSpread;
    for (size_t i = 0; i < res.onsets.size(); ++i) {
        const rg::Onset& o = res.onsets[i];
        size_t b = size_t(kHeader) + i * kFields;
        flat[b + 0] = o.timeSec;
        flat[b + 1] = o.strength;
        flat[b + 2] = o.low;
        flat[b + 3] = o.mid;
        flat[b + 4] = o.high;
        flat[b + 5] = o.centroid;
        flat[b + 6] = o.sustainSec;
    }

    size_t beatBase = size_t(kHeader) + res.onsets.size() * kFields;
    for (size_t i = 0; i < res.beats.size(); ++i) {
        size_t b = beatBase + i * kBeatFields;
        const rg::Beat& beat = res.beats[i];
        flat[b + 0] = beat.timeSec;
        flat[b + 1] = beat.confidence;
        flat[b + 2] = beat.strength;
        flat[b + 3] = float(beat.barIndex);
        flat[b + 4] = float(beat.positionInBar);
    }

    size_t envBase = beatBase + res.beats.size() * kBeatFields;
    for (size_t i = 0; i < res.envelope.size(); ++i) flat[envBase + i] = res.envelope[i];

    jfloatArray out = env->NewFloatArray(total);
    if (out) env->SetFloatArrayRegion(out, 0, total, flat.data());
    return out;
}

// --------------------------------------------------------------- клики ----
// Экземпляр, а не глобальный синглтон: два экрана (калибровка и игра)
// раньше дрались за один stream через общий start/stop.

jlong clickCreate(JNIEnv*, jobject) {
    auto* e = new rg::ClickEngine();
    if (!e->start()) { delete e; return 0; }
    return reinterpret_cast<jlong>(e);
}

void clickDestroy(JNIEnv*, jobject, jlong handle) {
    if (!handle) return;
    auto* e = reinterpret_cast<rg::ClickEngine*>(handle);
    e->stop();
    delete e;
}

void clickTrigger(JNIEnv*, jobject, jlong handle, jint variant) {
    if (handle) reinterpret_cast<rg::ClickEngine*>(handle)->trigger(int(variant));
}

void clickStartMetronome(JNIEnv*, jobject, jlong handle, jfloat periodSec) {
    if (handle) reinterpret_cast<rg::ClickEngine*>(handle)->startMetronome(double(periodSec));
}

void clickStopMetronome(JNIEnv*, jobject, jlong handle) {
    if (handle) reinterpret_cast<rg::ClickEngine*>(handle)->stopMetronome();
}

jlong clickLastNanos(JNIEnv*, jobject, jlong handle) {
    return handle ? jlong(reinterpret_cast<rg::ClickEngine*>(handle)->lastClickTimeNanos()) : 0;
}

jboolean installCrashHandler(JNIEnv* env, jobject, jstring path) {
    if (!path) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return JNI_FALSE;
    bool ok = rg::installCrashHandler(chars);
    env->ReleaseStringUTFChars(path, chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

const JNINativeMethod kDiagMethods[] = {
        { "nativeInstallCrashHandler", "(Ljava/lang/String;)Z",
          reinterpret_cast<void*>(installCrashHandler) },
};

const JNINativeMethod kAnalyzerMethods[] = {
        { "nativeAnalyze", "([FIFF)[F", reinterpret_cast<void*>(analyze) },
};

const JNINativeMethod kClickMethods[] = {
        { "nativeCreate",         "()J",   reinterpret_cast<void*>(clickCreate) },
        { "nativeDestroy",        "(J)V",  reinterpret_cast<void*>(clickDestroy) },
        { "nativeTrigger",        "(JI)V", reinterpret_cast<void*>(clickTrigger) },
        { "nativeStartMetronome", "(JF)V", reinterpret_cast<void*>(clickStartMetronome) },
        { "nativeStopMetronome",  "(J)V",  reinterpret_cast<void*>(clickStopMetronome) },
        { "nativeLastClickNanos", "(J)J",  reinterpret_cast<void*>(clickLastNanos) },
};

bool registerFor(JNIEnv* env, const char* className,
                 const JNINativeMethod* methods, int count) {
    jclass cls = env->FindClass(className);
    if (!cls) {
        LOGE("класс не найден: %s", className);
        return false;
    }
    if (env->RegisterNatives(cls, methods, count) != JNI_OK) {
        LOGE("RegisterNatives не прошёл для %s", className);
        return false;
    }
    env->DeleteLocalRef(cls);
    return true;
}

} // namespace

// Привязка по таблице, а не по именам символов: если пакет переименуют,
// падение произойдёт на System.loadLibrary с внятным сообщением,
// а не через полминуты игры при первом вызове.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (!registerFor(env, kAnalyzerClass, kAnalyzerMethods,
                     sizeof(kAnalyzerMethods) / sizeof(JNINativeMethod))) {
        return JNI_ERR;
    }
    if (!registerFor(env, kClickClass, kClickMethods,
                     sizeof(kClickMethods) / sizeof(JNINativeMethod))) {
        return JNI_ERR;
    }
    if (!registerFor(env, kDiagClass, kDiagMethods,
                     sizeof(kDiagMethods) / sizeof(JNINativeMethod))) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
