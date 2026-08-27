// analyzer.h — многоступенчатый анализ ритма. Чистый C++, без зависимостей.
#pragma once

#include <vector>
#include <cstdint>
#include <cstddef>

namespace rg {

/** Частотные полосы, по которым раздельно считается спектральный поток. */
enum Band { BAND_BASS = 0, BAND_LOWMID, BAND_MID, BAND_HIGH, BAND_COUNT };

struct Onset {
    float timeSec;
    float strength;   // ~1.0 у типичного сильного удара
    float low;        // доли энергии по полосам, в сумме 1
    float mid;
    float high;
    float centroid;   // спектральный центроид, Гц (прокси высоты тона)
    /**
     * Длительность звучания после удара, сек.
     *
     * Короткий перкуссионный удар даёт почти ноль, тянущаяся нота или
     * аккорд — сотни миллисекунд.
     */
    float sustainSec;
};

/** Одна доля с музыкальным контекстом. */
struct Beat {
    float timeSec;
    /** Насколько уверенно доля опирается на реальный звук, 0..1. */
    float confidence;
    /** Сила доли: энергия удара с упором на низ, 0..1+. */
    float strength;
    /** Порядковый номер доли от начала трека. */
    int32_t beatIndex;
    /** Номер такта; -1, если размер определить не удалось. */
    int32_t barIndex;
    /** Позиция внутри такта: 0 — сильная доля. */
    int32_t positionInBar;
};

/** Кандидат темпа со всеми оценками — попадает в отладочный вывод. */
struct TempoCandidate {
    float bpm;
    float phaseSec;
    /** Доля узлов сетки, под которыми есть реальный удар. */
    float coverage;
    /** Доля сильных ударов, попавших на узел сетки. */
    float recall;
    /** Гармоническое среднее покрытия и охвата. */
    float score;
};

struct AnalysisResult {
    float bpm = 0.f;          // средний темп
    float firstBeatSec = 0.f; // время первой доли
    float durationSec = 0.f;
    float confidence = 0.f;   // 0..1, насколько уверенно найден ритм
    /** Размер такта: 4 или 3; 0 — определить не удалось. */
    int32_t meter = 0;
    /** Разброс темпа по треку, BPM: большой означает плавающий темп. */
    float tempoSpread = 0.f;

    std::vector<Onset> onsets;
    std::vector<Beat> beats;

    /** Рассмотренные кандидаты темпа, отсортированы по убыванию оценки. */
    std::vector<TempoCandidate> candidates;

    /**
     * Огибающая спектра по всему треку для реактивного фона.
     * Три полосы (низ, середина, верх), 0..1, с шагом envelopeHz.
     */
    std::vector<float> envelope;
    float envelopeHz = 0.f;
};

struct AnalyzerConfig {
    int   frameSize   = 512;   // окно STFT (в отсчётах рабочей частоты)
    int   hopSize     = 128;   // шаг STFT (~5.8 мс при 22050)
    int   targetRate  = 22050; // рабочая частота; вход децимируется до неё
    float minOnsetGap = 0.045f;// минимальный интервал между онсетами, сек
    float thresholdK  = 1.35f; // множитель адаптивного порога над медианой
    float thresholdDelta = 0.02f;
    int   medianWin   = 41;    // окно медианного фильтра порога, кадров

    // Диапазон поиска темпа
    float minBpm      = 60.f;
    float maxBpm      = 200.f;

    // Отслеживание долей
    float tempoWindowSec = 8.f;   // окно оценки локального темпа
    float tempoHopSec    = 2.f;   // шаг между окнами
    float tightness      = 90.f;  // штраф за отклонение от локального периода
    /** Насколько локальный темп может отходить от глобального, доля. */
    float tempoDrift     = 0.18f;

    /** Допуск при проверке совпадения узла сетки с ударом, сек. */
    float gridTolerance  = 0.045f;

    float sustainDropDb  = 12.f;  // спад от пика, считающийся концом звучания
    float sustainMaxSec  = 2.5f;
    float envelopeHz     = 30.f;

    /** Писать подробности разбора в лог. */
    bool  verbose        = true;
};

// samples — моно float PCM в диапазоне [-1, 1].
AnalysisResult analyze(const float* samples, size_t count, int sampleRate,
                       const AnalyzerConfig& cfg = AnalyzerConfig());

/** Точка подключения лога: на Android сюда подставляется __android_log_print. */
void setLogSink(void (*sink)(const char* message));

} // namespace rg
