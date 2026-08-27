// click_engine.h — низколатентный вывод кликов через AAudio.
// SoundPool/MediaPlayer дают 100-200 мс задержки, для ритм-игры это неприемлемо.
#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <cstdint>

namespace rg {

class ClickEngine {
public:
    bool start();
    void stop();

    // Одиночный клик при попадании — воспроизводится в ближайшем колбэке.
    void trigger(int variant);

    // Метроном: периодические клики, сгенерированные точно по кадрам.
    void startMetronome(double periodSec);
    void stopMetronome();

    // Время (в наносекундах CLOCK_MONOTONIC), когда последний клик метронома
    // реально прозвучал на выходе. 0, если данных ещё нет.
    // Не const: спрашивает таймштамп у потока, поэтому вызывать только
    // из обычного потока, но НЕ из аудиоколбэка.
    int64_t lastClickTimeNanos();

    int sampleRate() const { return sampleRate_; }

private:
    static aaudio_data_callback_result_t dataCallback(
            AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
    void render(void* out, int32_t numFrames);
    float nextSample(int64_t absFrame);
    int64_t framesToNanos(int64_t frameIndex);

    AAudioStream* stream_ = nullptr;
    int sampleRate_ = 48000;

    // Формат и число каналов берутся у ОТКРЫТОГО потока, а не из запроса.
    // Устройство вправе выдать не то, что просили, и запись float'ов в
    // int16-буфер — это выход за границы и мгновенное падение процесса.
    int channelCount_ = 1;
    int32_t format_ = 0;

    int64_t framesWritten_ = 0;
    int     clickPos_ = -1;
    int     clickVariant_ = 0;
    uint32_t noiseState_ = 22222u;   // генератор шума для атаки
    std::atomic<int> pendingTrigger_{ -1 };

    std::atomic<bool> metronomeOn_{ false };
    int64_t metronomePeriodFrames_ = 0;
    int64_t nextMetronomeFrame_ = 0;
    // Колбэк пишет сюда только номер кадра; перевод во время
    // происходит снаружи, вне реального времени.
    std::atomic<int64_t> lastClickFrame_{ -1 };
};

} // namespace rg
