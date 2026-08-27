#include "click_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>

namespace rg {
namespace {
constexpr int kClickFrames = 1500; // ~31 мс при 48 кГц
}

aaudio_data_callback_result_t ClickEngine::dataCallback(
        AAudioStream* /*stream*/, void* userData, void* audioData, int32_t numFrames) {
    static_cast<ClickEngine*>(userData)->render(audioData, numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// Вызывается из Kotlin, а не из аудиоколбэка.
int64_t ClickEngine::lastClickTimeNanos() {
    int64_t frame = lastClickFrame_.load();
    if (frame < 0) return 0;
    return framesToNanos(frame);
}

int64_t ClickEngine::framesToNanos(int64_t frameIndex) {
    // Переводим индекс кадра в момент его реального воспроизведения,
    // опираясь на таймштамп потока (он уже учитывает выходную задержку).
    int64_t hwFrames = 0, hwNanos = 0;
    if (!stream_) return 0;
    if (AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &hwFrames, &hwNanos) != AAUDIO_OK)
        return 0;
    double deltaSec = double(frameIndex - hwFrames) / double(sampleRate_);
    return hwNanos + int64_t(deltaSec * 1e9);
}

// Один моно-отсчёт огибающей клика для абсолютного номера кадра.
float ClickEngine::nextSample(int64_t absFrame) {
    if (metronomeOn_.load() && metronomePeriodFrames_ > 0) {
        if (absFrame >= nextMetronomeFrame_) {
            clickPos_ = 0;
            clickVariant_ = 0;
            // В колбэке реального времени только запоминаем НОМЕР кадра.
            // AAudioStream_getTimestamp — блокирующий системный вызов, и
            // вызывать его отсюда значит ловить прерывания звука.
            lastClickFrame_.store(absFrame);
            nextMetronomeFrame_ += metronomePeriodFrames_;
        }
    }

    if (clickPos_ < 0) return 0.f;
    if (clickPos_ >= kClickFrames) { clickPos_ = -1; return 0.f; }

    const float sr = float(sampleRate_);
    const float x = float(clickPos_);
    const float tSec = x / sr;
    ++clickPos_;

    if (clickVariant_ == 0) {
        // Метроном: чистый короткий тон, его задача — быть точным ориентиром,
        // а не красивым.
        float env = std::exp(-tSec / 0.012f);
        return 0.40f * env * std::sin(2.f * float(M_PI) * 880.f * tSec);
    }

    // Звук попадания собран из слоёв, но КОРОТКИХ.
    //
    // В ритм-игре резкость важнее тембра: длинный звук с гудящим низом
    // размазывает ощущение момента удара, и попадание перестаёт читаться
    // на слух. Поэтому основная энергия укладывается в первые 10 мс,
    // а низ убран почти полностью — он маскирует собственную атаку.

    // 1. Атака: короткий шумовой всплеск, он и даёт отчётливость.
    noiseState_ = noiseState_ * 1664525u + 1013904223u;
    float noise = (float(int32_t(noiseState_ >> 8) & 0xFFFF) / 32768.f) - 1.f;
    float attackEnv = std::exp(-tSec / 0.0018f);
    float attack = 0.42f * attackEnv * noise;

    // 2. Тело: два тона в квинту, чтобы звук читался как нота, а не как стук.
    float bodyEnv = std::exp(-tSec / 0.016f);
    float body = bodyEnv * (
            0.34f * std::sin(2.f * float(M_PI) * 1480.f * tSec) +
            0.18f * std::sin(2.f * float(M_PI) * 2220.f * tSec));

    // 3. Лёгкий вес без гудения: быстрый спад, иначе низ живёт дольше
    // самого удара и сливается с музыкой.
    float subEnv = std::exp(-tSec / 0.014f);
    float sub = 0.14f * subEnv * std::sin(2.f * float(M_PI) * 330.f * tSec);

    float v = attack + body + sub;
    // Мягкое ограничение вместо жёсткого среза: срез даёт хруст.
    return std::tanh(v * 1.2f) * 0.8f;
}

void ClickEngine::render(void* out, int32_t numFrames) {
    const size_t samples = size_t(numFrames) * size_t(channelCount_);

    int pending = pendingTrigger_.exchange(-1);
    if (pending >= 0) { clickPos_ = 0; clickVariant_ = pending; }

    if (format_ == AAUDIO_FORMAT_PCM_I16) {
        int16_t* dst = static_cast<int16_t*>(out);
        std::memset(dst, 0, samples * sizeof(int16_t));
        for (int32_t i = 0; i < numFrames; ++i) {
            float v = nextSample(framesWritten_ + i);
            int16_t s = int16_t(std::lround(std::clamp(v, -1.f, 1.f) * 32767.f));
            // Один и тот же сигнал во все каналы: поток мог открыться стерео.
            for (int c = 0; c < channelCount_; ++c)
                dst[size_t(i) * size_t(channelCount_) + size_t(c)] = s;
        }
    } else {
        float* dst = static_cast<float*>(out);
        std::memset(dst, 0, samples * sizeof(float));
        for (int32_t i = 0; i < numFrames; ++i) {
            float v = nextSample(framesWritten_ + i);
            for (int c = 0; c < channelCount_; ++c)
                dst[size_t(i) * size_t(channelCount_) + size_t(c)] = v;
        }
    }

    framesWritten_ += numFrames;
}

bool ClickEngine::start() {
    if (stream_) return true;

    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;

    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 1);
    AAudioStreamBuilder_setDataCallback(b, dataCallback, this);

    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream_);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !stream_) { stream_ = nullptr; return false; }

    // То, что реально дало устройство. Запрос — это только пожелание.
    sampleRate_   = AAudioStream_getSampleRate(stream_);
    channelCount_ = AAudioStream_getChannelCount(stream_);
    format_       = AAudioStream_getFormat(stream_);

    if (sampleRate_ <= 0) sampleRate_ = 48000;
    if (channelCount_ <= 0) channelCount_ = 1;

    // Незнакомый формат лучше не трогать вовсе: писать в чужой буфер —
    // верное падение процесса. Звук попаданий просто не будет играть.
    if (format_ != AAUDIO_FORMAT_PCM_FLOAT && format_ != AAUDIO_FORMAT_PCM_I16) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
        return false;
    }

    // Буфер в 2 burst'а — компромисс между задержкой и устойчивостью к underrun.
    int32_t burst = AAudioStream_getFramesPerBurst(stream_);
    if (burst > 0) AAudioStream_setBufferSizeInFrames(stream_, burst * 2);

    framesWritten_ = 0;
    clickPos_ = -1;
    if (AAudioStream_requestStart(stream_) != AAUDIO_OK) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
        return false;
    }
    return true;
}

void ClickEngine::stop() {
    if (!stream_) return;
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
}

void ClickEngine::trigger(int variant) {
    pendingTrigger_.store(variant);
}

void ClickEngine::startMetronome(double periodSec) {
    metronomePeriodFrames_ = int64_t(periodSec * double(sampleRate_));
    // Стартуем не раньше чем через 0.5 с, чтобы первый клик не съел буфер.
    nextMetronomeFrame_ = framesWritten_ + int64_t(0.5 * double(sampleRate_));
    metronomeOn_.store(true);
}

void ClickEngine::stopMetronome() {
    metronomeOn_.store(false);
}

} // namespace rg
