#include "analyzer.h"
#include "fft.h"

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <numeric>

namespace rg {

namespace {

void (*gLogSink)(const char*) = nullptr;

/** Измеренная поправка на раннее срабатывание детектора, сек. */
constexpr float kDetectionBiasSec = 0.0055f;

/** Ниже этой уверенности доля на краю трека считается достроенной в тишину. */
constexpr float kEdgeConfidence = 0.02f;

void logf(bool verbose, const char* fmt, ...) {
    if (!verbose || !gLogSink) return;
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    gLogSink(buf);
}

// ------------------------------------------------------------------ утилиты --

std::vector<float> decimate(const float* in, size_t count, int srcRate,
                            int targetRate, int& outRate) {
    int factor = std::max(1, int(std::lround(double(srcRate) / double(targetRate))));
    outRate = srcRate / factor;
    std::vector<float> out;
    out.reserve(count / size_t(factor) + 1);
    for (size_t i = 0; i + size_t(factor) <= count; i += size_t(factor)) {
        float acc = 0.f;
        for (int j = 0; j < factor; ++j) acc += in[i + size_t(j)];
        out.push_back(acc / float(factor));
    }
    return out;
}

float medianOf(std::vector<float>& v) {
    if (v.empty()) return 0.f;
    size_t m = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + long(m), v.end());
    return v[m];
}

void normalizeToPeak(std::vector<float>& v) {
    float m = 0.f;
    for (float x : v) m = std::max(m, x);
    if (m <= 1e-9f) return;
    for (float& x : v) x /= m;
}

// --------------------------------------------------- многополосный поток ----

/** Спектральный поток по каждой полосе плюс сводный. */
struct BandFlux {
    std::vector<float> band[BAND_COUNT];
    std::vector<float> combined;
    std::vector<float> energy;    // полная энергия кадра
    std::vector<float> bandEnergy[BAND_COUNT];
    std::vector<float> centroid;
    size_t frames = 0;
};

/**
 * Разбор спектра.
 *
 * Поток считается ОТДЕЛЬНО в четырёх полосах, а не только суммарно.
 * Смысл: бочка, бас, середина и перкуссия ведут себя по-разному, и для
 * определения фазы ритма басовая полоса решает почти всё, тогда как в
 * сводном потоке она тонет среди хай-хэтов, которых вдвое больше.
 */
BandFlux computeFlux(const std::vector<float>& x, int rate, const AnalyzerConfig& cfg) {
    BandFlux out;
    const size_t N = size_t(cfg.frameSize);
    const size_t H = size_t(cfg.hopSize);
    if (x.size() < N * 2) return out;

    std::vector<float> window(N);
    for (size_t i = 0; i < N; ++i)
        window[i] = 0.5f * (1.f - std::cos(2.f * float(M_PI) * float(i) / float(N - 1)));

    Fft fft(N);
    const size_t bins = N / 2 + 1;
    const float binHz = float(rate) / float(N);

    auto binOf = [&](float hz) {
        double b = double(hz) / double(binHz);
        return size_t(std::min(double(bins - 1), std::max(0.0, b)));
    };
    // Границы полос выбраны по инструментам: бочка и бас, тело микса,
    // вокал и гармония, перкуссия.
    const size_t edge[BAND_COUNT + 1] = {
        binOf(20.f), binOf(150.f), binOf(800.f), binOf(3000.f), bins - 1
    };

    const size_t frames = (x.size() - N) / H + 1;
    out.frames = frames;
    for (int b = 0; b < BAND_COUNT; ++b) {
        out.band[b].assign(frames, 0.f);
        out.bandEnergy[b].assign(frames, 0.f);
    }
    out.combined.assign(frames, 0.f);
    out.energy.assign(frames, 0.f);
    out.centroid.assign(frames, 0.f);

    std::vector<float> prevMag(bins, 0.f), mag(bins, 0.f);
    std::vector<float> re(N), im(N);

    for (size_t f = 0; f < frames; ++f) {
        const float* src = &x[f * H];
        for (size_t i = 0; i < N; ++i) { re[i] = src[i] * window[i]; im[i] = 0.f; }
        fft.forward(re.data(), im.data());

        float cNum = 0.f, cDen = 0.f, total = 0.f;
        float flux[BAND_COUNT] = { 0.f, 0.f, 0.f, 0.f };
        float energy[BAND_COUNT] = { 0.f, 0.f, 0.f, 0.f };

        for (size_t k = 0; k < bins; ++k) {
            float m = std::sqrt(re[k] * re[k] + im[k] * im[k]);
            mag[k] = m;

            int b = 0;
            while (b < BAND_COUNT - 1 && k >= edge[b + 1]) ++b;
            energy[b] += m;
            total += m;
            cNum += m * float(k) * binHz;
            cDen += m;

            // Поток в лог-домене: иначе тихие пассажи проваливаются
            // относительно громких, и половина трека остаётся без ударов.
            float d = std::log1p(m * 100.f) - std::log1p(prevMag[k] * 100.f);
            if (d > 0.f) flux[b] += d;
        }

        for (int b = 0; b < BAND_COUNT; ++b) {
            out.band[b][f] = flux[b];
            out.bandEnergy[b][f] = energy[b];
        }
        out.energy[f] = total;
        out.centroid[f] = cDen > 1e-9f ? cNum / cDen : 0.f;
        prevMag.swap(mag);
    }

    // Каждую полосу нормируем отдельно: у баса энергия на порядок выше,
    // и без этого верхние полосы не влияли бы ни на что.
    for (int b = 0; b < BAND_COUNT; ++b) normalizeToPeak(out.band[b]);

    // Сводный поток: перкуссия важна для плотности, бас — для пульса.
    static const float weight[BAND_COUNT] = { 1.0f, 0.8f, 0.9f, 0.7f };
    for (size_t f = 0; f < frames; ++f) {
        float acc = 0.f;
        for (int b = 0; b < BAND_COUNT; ++b) acc += weight[b] * out.band[b][f];
        out.combined[f] = acc;
    }
    normalizeToPeak(out.combined);

    // Трёхточечное сглаживание убирает дребезг пиков.
    std::vector<float> smooth(frames);
    for (size_t f = 0; f < frames; ++f) {
        float a = f > 0 ? out.combined[f - 1] : out.combined[f];
        float c = f + 1 < frames ? out.combined[f + 1] : out.combined[f];
        smooth[f] = 0.25f * a + 0.5f * out.combined[f] + 0.25f * c;
    }
    out.combined.swap(smooth);
    return out;
}

// ------------------------------------------------------------- пик-пикинг ----

struct Peak { size_t frame; float value; float timeSec; };

std::vector<Peak> pickPeaks(const std::vector<float>& odf, float frameSec,
                            float groupDelay, const AnalyzerConfig& cfg) {
    const size_t frames = odf.size();
    std::vector<Peak> kept;
    if (frames < 3) return kept;

    // Адаптивный порог: скользящая медиана * k + delta.
    const int half = cfg.medianWin / 2;
    std::vector<float> win;
    win.reserve(size_t(cfg.medianWin));

    std::vector<Peak> peaks;
    for (size_t f = 1; f + 1 < frames; ++f) {
        size_t a = f > size_t(half) ? f - size_t(half) : 0;
        size_t b = std::min(frames, f + size_t(half) + 1);
        win.assign(odf.begin() + long(a), odf.begin() + long(b));
        float thr = medianOf(win) * cfg.thresholdK + cfg.thresholdDelta;
        if (odf[f] < thr) continue;
        if (odf[f] < odf[f - 1] || odf[f] < odf[f + 1]) continue;

        // Параболическая интерполяция вершины — субкадровая точность.
        float y0 = odf[f - 1], y1 = odf[f], y2 = odf[f + 1];
        float denom = y0 - 2.f * y1 + y2;
        float delta = std::fabs(denom) > 1e-9f ? 0.5f * (y0 - y2) / denom : 0.f;
        delta = std::max(-0.5f, std::min(0.5f, delta));
        peaks.push_back({ f, y1, (float(f) + delta) * frameSec + groupDelay });
    }

    for (const Peak& p : peaks) {
        if (!kept.empty() && p.timeSec - kept.back().timeSec < cfg.minOnsetGap) {
            if (p.value > kept.back().value) kept.back() = p;
            continue;
        }
        kept.push_back(p);
    }
    return kept;
}

// ------------------------------------------------------- кандидаты темпа ----

/**
 * Оценка кандидата темпа по ЭНЕРГИИ ударов, а не по их количеству.
 *
 * Считать факт попадания недостаточно: у трека с хай-хэтами на слабых долях
 * сетка вдвое быстрее покрывает и бочки, и хэты, и по количеству выглядит
 * безупречно. Разница видна только по силе — на верном темпе под узлами
 * стоят СИЛЬНЫЕ удары, на удвоенном половина узлов приходится на слабые.
 *
 *   точность = средняя сила удара под узлом сетки
 *              (падает при удвоении темпа: половина узлов на хэтах);
 *   охват    = какая доля всей силы ударов легла на узлы
 *              (падает при половинном темпе: половина ударов мимо).
 *
 * Гармоническое среднее наказывает перекос в любую сторону.
 */
TempoCandidate scoreCandidate(double periodSec, double phaseSec,
                              const std::vector<Peak>& onsets,
                              float durationSec, float tolerance) {
    TempoCandidate c;
    c.bpm = float(60.0 / periodSec);
    c.phaseSec = float(phaseSec);
    c.coverage = 0.f;
    c.recall = 0.f;
    c.score = 0.f;
    if (periodSec <= 0.0 || onsets.empty()) return c;

    float maxStrength = 0.f;
    double totalStrength = 0.0;
    for (const Peak& p : onsets) {
        maxStrength = std::max(maxStrength, p.value);
        totalStrength += double(p.value);
    }
    if (maxStrength <= 1e-9f || totalStrength <= 1e-9) return c;

    // Для каждого узла — сила ближайшего удара в допуске (0, если пусто).
    size_t gridCount = 0;
    double sumAtGrid = 0.0;
    size_t cursor = 0;
    for (double t = phaseSec; t < durationSec; t += periodSec) {
        ++gridCount;
        while (cursor + 1 < onsets.size() && onsets[cursor].timeSec < t - tolerance) ++cursor;
        float best = 0.f;
        for (size_t k = cursor; k < onsets.size(); ++k) {
            if (onsets[k].timeSec > t + tolerance) break;
            best = std::max(best, onsets[k].value);
        }
        sumAtGrid += double(best);
    }
    if (gridCount == 0) return c;

    // Какая часть общей силы ударов попала на узлы.
    double captured = 0.0;
    for (const Peak& p : onsets) {
        double k = std::round((double(p.timeSec) - phaseSec) / periodSec);
        double gt = phaseSec + k * periodSec;
        if (std::fabs(double(p.timeSec) - gt) <= tolerance) captured += double(p.value);
    }

    c.coverage = float(sumAtGrid / double(gridCount) / double(maxStrength));
    c.recall = float(captured / totalStrength);

    // Ранжирование — по сумме сил под сеткой, делённой на КОРЕНЬ из числа
    // узлов. Эта нормировка сама уравновешивает обе крайности:
    //
    //   удвоенный темп — узлов вдвое больше, но половина стоит на слабых
    //   ударах, поэтому сумма растёт меньше, чем корень из числа узлов;
    //
    //   половинный темп — под каждым узлом сильный удар, но узлов вдвое
    //   меньше, и сумма падает быстрее корня.
    //
    // Никаких предположений о «правильном» темпе она не содержит.
    // Оценка приводится к 0..1.
    //
    // Сырая сумма сил ничем не ограничена, и итоговая уверенность трека
    // выдавала значения вроде 3.9 — на экране это выглядело мусором.
    //
    // Смысл прежний: средняя доля силы под узлом (падает при удвоении темпа),
    // умноженная на корень отношения числа узлов к эталонному (падает при
    // половинном темпе). Эталон — сетка с шагом в одну секунду. Ранжирование
    // кандидатов между собой не меняется.
    {
        double mean = sumAtGrid / double(gridCount) / double(maxStrength);
        double refCount = std::max(1.0, double(durationSec));
        double weight = std::sqrt(double(gridCount) / refCount);
        c.score = float(std::min(1.0, mean * weight));
    }

    // Мягкая поправка на восприятие: слух устойчивее всего держит пульс
    // около 120 ударов в минуту. Это ТОЛЬКО разрешение спора между
    // кандидатами с близкими оценками — раньше похожий множитель стоял
    // поверх автокорреляции, где никакой опоры на реальный звук не было.
    double lg = std::log2(double(c.bpm) / 120.0);
    double resonance = std::exp(-lg * lg / (2.0 * 0.55 * 0.55));
    c.score *= float(0.85 + 0.15 * resonance);
    return c;
}

/** Лучшая фаза для заданного периода — свёртка с гребёнкой по потоку. */
double bestPhase(const std::vector<float>& odf, double periodFrames, float frameSec) {
    const size_t frames = odf.size();
    const int steps = std::max(1, int(std::ceil(periodFrames)));
    double bestScore = -1.0;
    int best = 0;
    for (int p = 0; p < steps; ++p) {
        double s = 0.0;
        for (double pos = p; pos < double(frames); pos += periodFrames) {
            long i = long(std::lround(pos));
            if (i < 1 || i + 1 >= long(frames)) continue;
            // Соседние кадры с меньшим весом: пики потока имеют ширину.
            s += double(odf[size_t(i)])
               + 0.5 * double(odf[size_t(i - 1)])
               + 0.5 * double(odf[size_t(i + 1)]);
        }
        if (s > bestScore) { bestScore = s; best = p; }
    }
    return double(best) * double(frameSec);
}

// -------------------------------------------------- отслеживание долей ----

/**
 * Локальный период на каждый кадр, привязанный к глобальному темпу.
 *
 * Живая музыка плывёт, но резких скачков быть не должно: одиночный ложный
 * удар не имеет права утащить темп вдвое. Поэтому локальная оценка
 * зажимается вокруг глобального периода.
 */
/** Ширина допустимого дрейфа темпа в логарифмической шкале. */
constexpr double kDriftSigma = 0.035;

std::vector<double> tempoCurve(const std::vector<float>& odf, double frameSec,
                               double globalPeriod, const AnalyzerConfig& cfg) {
    const size_t frames = odf.size();
    std::vector<double> curve(frames, globalPeriod);
    if (frames < 8) return curve;

    const double lo = globalPeriod * (1.0 - cfg.tempoDrift);
    const double hi = globalPeriod * (1.0 + cfg.tempoDrift);
    const int lagMin = std::max(1, int(std::lround(lo)));
    const int lagMax = std::min(int(frames) - 1, int(std::lround(hi)));
    if (lagMax <= lagMin) return curve;

    const size_t win = size_t(std::max(1.0, cfg.tempoWindowSec / frameSec));
    const size_t hop = size_t(std::max(1.0, cfg.tempoHopSec / frameSec));

    std::vector<double> centers, periods;
    double mean = std::accumulate(odf.begin(), odf.end(), 0.0) / double(frames);

    for (size_t start = 0; start + 1 < frames; start += hop) {
        size_t end = std::min(frames, start + win);
        if (end <= start + size_t(lagMax) + 2) continue;

        double best = -1e18; int bestLag = lagMin;
        for (int lag = lagMin; lag <= lagMax; ++lag) {
            double acc = 0.0; size_t cnt = 0;
            for (size_t i = start + size_t(lag); i < end; ++i, ++cnt)
                acc += (double(odf[i]) - mean) * (double(odf[i - size_t(lag)]) - mean);
            if (!cnt) continue;
            acc /= double(cnt);

            // Штраф за отклонение от глобального периода.
            //
            // Без него локальная автокорреляция внутри окна дрейфа находит
            // побочный период и утаскивает за собой всю сетку — темп при
            // этом был выбран верно, а доли всё равно уезжали. Дрейф
            // разрешён, но каждый процент отклонения чего-то стоит.
            double rel = std::log(double(lag) / globalPeriod);
            acc *= std::exp(-rel * rel / (2.0 * kDriftSigma * kDriftSigma));

            if (acc > best) { best = acc; bestLag = lag; }
        }
        centers.push_back(double(start + end) * 0.5);
        periods.push_back(double(bestLag));
    }

    if (periods.empty()) return curve;

    // Медианное сглаживание: одно окно с шумом не должно ломать кривую.
    std::vector<double> smooth(periods.size());
    for (size_t i = 0; i < periods.size(); ++i) {
        size_t a = i > 1 ? i - 2 : 0;
        size_t b = std::min(periods.size(), i + 3);
        std::vector<double> w(periods.begin() + long(a), periods.begin() + long(b));
        std::nth_element(w.begin(), w.begin() + long(w.size() / 2), w.end());
        smooth[i] = w[w.size() / 2];
    }

    for (size_t f = 0; f < frames; ++f) {
        double x = double(f);
        if (x <= centers.front()) { curve[f] = smooth.front(); continue; }
        if (x >= centers.back())  { curve[f] = smooth.back();  continue; }
        size_t i = 1;
        while (i < centers.size() && centers[i] < x) ++i;
        double t = (x - centers[i - 1]) / std::max(1e-9, centers[i] - centers[i - 1]);
        curve[f] = smooth[i - 1] * (1.0 - t) + smooth[i] * t;
    }
    return curve;
}

/**
 * Отслеживание долей динамическим программированием.
 *
 * Доля ставится туда, где сильный удар, но интервал между соседними долями
 * штрафуется за отклонение от локального периода. Дополнительно поощряется
 * попадание в найденную глобальную фазу — это удерживает сетку от сползания
 * на слабые доли в местах, где бочка молчит.
 */
std::vector<size_t> trackBeats(const std::vector<float>& odf,
                               const std::vector<double>& period,
                               double phaseFrames, double globalPeriod,
                               double tightness) {
    const size_t n = odf.size();
    std::vector<size_t> beats;
    if (n < 4) return beats;

    // Приз за совпадение с глобальной сеткой.
    std::vector<float> prior(n, 0.f);
    if (globalPeriod > 1.0) {
        for (size_t f = 0; f < n; ++f) {
            double k = std::round((double(f) - phaseFrames) / globalPeriod);
            double gt = phaseFrames + k * globalPeriod;
            double d = std::fabs(double(f) - gt) / globalPeriod;
            prior[f] = float(0.35 * std::exp(-d * d / 0.005));
        }
    }

    std::vector<double> score(n, 0.0);
    std::vector<long> prev(n, -1);

    for (size_t t = 0; t < n; ++t) {
        double tau = period[t];
        double self = double(odf[t]) + double(prior[t]);
        if (tau < 2.0) { score[t] = self; continue; }

        long lo = long(t) - long(std::lround(tau * 1.6));
        long hi = long(t) - long(std::lround(tau * 0.6));
        if (hi < 1) { score[t] = self; continue; }
        if (lo < 0) lo = 0;

        double best = -1e18; long bestPrev = -1;
        for (long lag = lo; lag <= hi; ++lag) {
            double d = double(long(t) - lag);
            if (d <= 0.0) continue;
            double ratio = std::log(d / tau);
            double v = score[size_t(lag)] - tightness * ratio * ratio;
            if (v > best) { best = v; bestPrev = lag; }
        }
        score[t] = self + (bestPrev >= 0 ? best : 0.0);
        prev[t] = bestPrev;
    }

    // Откат начинаем с лучшего в последней четверти: хвост часто затухает,
    // и глобальный максимум может оказаться там, где музыка уже кончилась.
    size_t from = n * 3 / 4;
    size_t bestEnd = from;
    double bestVal = -1e18;
    for (size_t t = from; t < n; ++t)
        if (score[t] > bestVal) { bestVal = score[t]; bestEnd = t; }

    long cur = long(bestEnd);
    while (cur >= 0) {
        beats.push_back(size_t(cur));
        cur = prev[size_t(cur)];
    }
    std::reverse(beats.begin(), beats.end());
    return beats;
}

/**
 * Размер такта и положение сильной доли.
 *
 * Сильная доля почти всегда несёт больше низа: бочка на «раз». Перебираем
 * размеры 4 и 3 и все сдвиги внутри такта, выбираем сочетание, у которого
 * басовая энергия на сильных долях сильнее всего превышает среднюю.
 */
void detectMeter(const std::vector<size_t>& beatFrames,
                 const std::vector<float>& bassFlux,
                 int& meterOut, int& offsetOut, float& strengthOut) {
    meterOut = 0; offsetOut = 0; strengthOut = 0.f;
    if (beatFrames.size() < 8) return;

    std::vector<float> value(beatFrames.size(), 0.f);
    for (size_t i = 0; i < beatFrames.size(); ++i) {
        size_t f = beatFrames[i];
        float acc = 0.f;
        for (long d = -2; d <= 2; ++d) {
            long k = long(f) + d;
            if (k >= 0 && k < long(bassFlux.size())) acc = std::max(acc, bassFlux[size_t(k)]);
        }
        value[i] = acc;
    }
    double overall = std::accumulate(value.begin(), value.end(), 0.0) / double(value.size());
    if (overall <= 1e-9) return;

    double bestRatio = 0.0;
    for (int meter : { 4, 3 }) {
        for (int off = 0; off < meter; ++off) {
            double sum = 0.0; size_t cnt = 0;
            for (size_t i = size_t(off); i < value.size(); i += size_t(meter)) {
                sum += value[i]; ++cnt;
            }
            if (!cnt) continue;
            double ratio = (sum / double(cnt)) / overall;
            if (ratio > bestRatio) {
                bestRatio = ratio; meterOut = meter; offsetOut = off;
            }
        }
    }
    strengthOut = float(bestRatio);
    // Слабое превышение означает, что тактовой структуры не слышно.
    if (bestRatio < 1.12) { meterOut = 0; offsetOut = 0; }
}

/**
 * Проверка на удвоение темпа по чередованию силы.
 *
 * Если выбранный темп вдвое выше настоящего, узлы сетки чередуются:
 * сильный (бочка), слабый (хай-хэт), сильный, слабый. Настоящий темп
 * такого чередования не даёт — там все узлы примерно равны.
 *
 * Признак прямой и не требует догадок о «правильном» диапазоне темпа.
 *
 * @return true, если темп следует уполовинить; в phaseOut кладётся фаза
 *         сильной половины узлов.
 */
constexpr double kAlternationRatio = 0.75;
float gLastAlternation = 1.f;
/** Насколько может просесть оценка при уполовинивании, чтобы его принять. */
constexpr double kHalvingScoreFloor = 0.85;

bool detectDoubling(double periodSec, double phaseSec,
                    const std::vector<Peak>& onsets,
                    float durationSec, float tolerance,
                    double& phaseOut) {
    if (onsets.empty()) return false;

    double sum[2] = { 0.0, 0.0 };
    size_t cnt[2] = { 0, 0 };
    size_t index = 0, cursor = 0;
    for (double t = phaseSec; t < durationSec; t += periodSec, ++index) {
        while (cursor + 1 < onsets.size() && onsets[cursor].timeSec < t - tolerance) ++cursor;
        float best = 0.f;
        for (size_t k = cursor; k < onsets.size(); ++k) {
            if (onsets[k].timeSec > t + tolerance) break;
            best = std::max(best, onsets[k].value);
        }
        sum[index & 1] += double(best);
        ++cnt[index & 1];
    }
    if (cnt[0] < 4 || cnt[1] < 4) return false;

    double mean0 = sum[0] / double(cnt[0]);
    double mean1 = sum[1] / double(cnt[1]);
    double lo = std::min(mean0, mean1), hi = std::max(mean0, mean1);
    if (hi <= 1e-9) return false;

    // Порог подобран на синтетических треках: настоящее чередование
    // бочка/хэт даёт разницу заметно больше полутора раз.
    gLastAlternation = float(lo / hi);
    if (lo / hi > kAlternationRatio) return false;

    phaseOut = (mean0 >= mean1) ? phaseSec : phaseSec + periodSec;
    return true;
}

} // namespace

void setLogSink(void (*sink)(const char*)) { gLogSink = sink; }

// =========================================================== главный вход ====

AnalysisResult analyze(const float* samples, size_t count, int sampleRate,
                       const AnalyzerConfig& cfg) {
    AnalysisResult res;
    if (!samples || count == 0 || sampleRate <= 0) return res;
    res.durationSec = float(count) / float(sampleRate);

    int rate = sampleRate;
    std::vector<float> x = decimate(samples, count, sampleRate, cfg.targetRate, rate);

    BandFlux flux = computeFlux(x, rate, cfg);
    if (flux.frames == 0) return res;

    const size_t frames = flux.frames;
    const float frameSec = float(cfg.hopSize) / float(rate);
    // Компенсация задержки детектора.
    //
    // Первая часть — групповая задержка STFT: транзиент опознаётся, когда
    // приходится примерно на центр окна.
    //
    // Вторая — измеренная поправка. Поток в лог-домене начинает расти ещё
    // до полного развития атаки, поэтому пик приходит чуть раньше реального
    // удара. Замер по семи жанрам дал устойчивые -4.0..-6.6 мс, среднее
    // -5.5 мс; вычитание постоянной величины убирает перекос целиком.
    const float groupDelay = float(cfg.frameSize) * 0.5f / float(rate)
                           + kDetectionBiasSec;

    logf(cfg.verbose, "[takt] длительность %.1f с, кадров %zu, шаг %.2f мс",
         res.durationSec, frames, frameSec * 1000.f);

    // ---- Онсеты ----
    std::vector<Peak> peaks = pickPeaks(flux.combined, frameSec, groupDelay, cfg);
    if (peaks.empty()) {
        logf(cfg.verbose, "[takt] ударов не найдено");
        return res;
    }

    {
        std::vector<float> vals;
        vals.reserve(peaks.size());
        for (const Peak& p : peaks) vals.push_back(p.value);
        size_t idx = size_t(double(vals.size() - 1) * 0.9);
        std::nth_element(vals.begin(), vals.begin() + long(idx), vals.end());
        float scale = std::max(1e-6f, vals[idx]);

        const size_t maxSustainFrames = size_t(cfg.sustainMaxSec / frameSec);
        res.onsets.reserve(peaks.size());
        for (const Peak& p : peaks) {
            size_t f = p.frame;
            Onset on;
            on.timeSec = p.timeSec;
            on.strength = std::min(2.f, p.value / scale);

            float lowE = flux.bandEnergy[BAND_BASS][f] + flux.bandEnergy[BAND_LOWMID][f];
            float midE = flux.bandEnergy[BAND_MID][f];
            float highE = flux.bandEnergy[BAND_HIGH][f];
            float sum = lowE + midE + highE + 1e-9f;
            on.low = lowE / sum;
            on.mid = midE / sum;
            on.high = highE / sum;
            on.centroid = flux.centroid[f];

            // Длительность звучания: сколько держится энергия после удара.
            float peakE = flux.energy[f];
            for (size_t k = f; k < std::min(frames, f + 4); ++k)
                peakE = std::max(peakE, flux.energy[k]);
            const float threshold = peakE * std::pow(10.f, -cfg.sustainDropDb / 20.f);
            size_t k = f;
            size_t limit = std::min(frames, f + maxSustainFrames);
            while (k < limit && flux.energy[k] >= threshold) ++k;
            on.sustainSec = float(k - f) * frameSec;

            res.onsets.push_back(on);
        }
    }
    logf(cfg.verbose, "[takt] ударов найдено: %zu (%.2f/сек)",
         res.onsets.size(), res.onsets.size() / std::max(1.f, res.durationSec));

    // ---- Кандидаты темпа ----
    // Пульс ищем по потоку с усилением низа: доля почти всегда на бочке.
    std::vector<float> pulseOdf(frames);
    for (size_t f = 0; f < frames; ++f) {
        pulseOdf[f] = flux.combined[f] + 2.0f * flux.band[BAND_BASS][f];
    }
    normalizeToPeak(pulseOdf);

    const int lagMin = std::max(1, int(std::lround(60.0 / double(cfg.maxBpm) / double(frameSec))));
    const int lagMax = std::min(int(frames) - 1,
                                int(std::lround(60.0 / double(cfg.minBpm) / double(frameSec))));
    if (lagMax <= lagMin) return res;

    // Оценивать кандидатов надо по ТОМУ ЖЕ потоку, по которому ищется фаза.
    // Раньше фаза бралась из потока с усилением низа, а оценка — из сводного,
    // где хай-хэт не слабее бочки, и бочка переставала выделяться.
    std::vector<Peak> pulsePeaks = peaks;
    for (Peak& p : pulsePeaks) {
        float v = 0.f;
        for (long d = -1; d <= 1; ++d) {
            long k = long(p.frame) + d;
            if (k >= 0 && k < long(frames)) v = std::max(v, pulseOdf[size_t(k)]);
        }
        p.value = v;
    }

    // Темп перебирается СПЛОШЬ по всему диапазону, а не берётся из пиков
    // автокорреляции. Пики капризны: у них своя форма, и настоящий темп
    // нередко оказывается не первым и даже не пятым. Проверка одного
    // кандидата стоит один проход по треку, поэтому сплошной перебор
    // дешевле и надёжнее. Все октавные варианты попадают в него сами.
    std::vector<double> periodCandidates;
    for (double bpm = double(cfg.minBpm); bpm <= double(cfg.maxBpm); bpm += 0.5) {
        double periodFrames = 60.0 / bpm / double(frameSec);
        if (periodFrames < double(lagMin) || periodFrames > double(lagMax)) continue;
        periodCandidates.push_back(periodFrames);
    }
    if (periodCandidates.empty()) periodCandidates.push_back(double(lagMin + lagMax) / 2.0);

    for (double periodFrames : periodCandidates) {
        double phase = bestPhase(pulseOdf, periodFrames, frameSec);
        TempoCandidate c = scoreCandidate(periodFrames * double(frameSec), phase,
                                          pulsePeaks, res.durationSec, cfg.gridTolerance);
        res.candidates.push_back(c);
    }
    std::sort(res.candidates.begin(), res.candidates.end(),
              [](const TempoCandidate& a, const TempoCandidate& b) { return a.score > b.score; });

    for (size_t i = 0; i < std::min<size_t>(5, res.candidates.size()); ++i) {
        const TempoCandidate& c = res.candidates[i];
        logf(cfg.verbose,
             "[takt] кандидат %zu: %.2f BPM фаза %.3f с | покрытие %.2f охват %.2f оценка %.3f",
             i + 1, c.bpm, c.phaseSec, c.coverage, c.recall, c.score);
    }

    TempoCandidate winner = res.candidates.front();

    // Проверка на удвоение: чередование сильных и слабых узлов означает,
    // что настоящая доля вдвое длиннее.
    {
        double halvedPhase = 0.0;
        double periodSec = 60.0 / double(winner.bpm);
        bool doubled = detectDoubling(periodSec, double(winner.phaseSec), pulsePeaks,
                                      res.durationSec, cfg.gridTolerance, halvedPhase);
        logf(cfg.verbose, "[takt] чередование силы узлов: %.3f (порог %.2f)",
             gLastAlternation, kAlternationRatio);
        if (doubled) {
            TempoCandidate halved = scoreCandidate(periodSec * 2.0, halvedPhase, pulsePeaks,
                                                   res.durationSec, cfg.gridTolerance);
            // Чередование бывает и на ВЕРНОМ темпе — так звучит полутемповый
            // рэп: бочка, хэт, малый, хэт. Отличие в том, что там половина
            // ударов при уполовинивании теряется и оценка рушится.
            if (halved.bpm >= cfg.minBpm &&
                double(halved.score) >= double(winner.score) * kHalvingScoreFloor) {
                logf(cfg.verbose,
                     "[takt] обнаружено удвоение: %.2f -> %.2f BPM (фаза %.3f с)",
                     winner.bpm, halved.bpm, halved.phaseSec);
                winner = halved;
                res.candidates.insert(res.candidates.begin(), halved);
            }
        }
    }

    const double globalPeriodFrames = 60.0 / double(winner.bpm) / double(frameSec);
    const double phaseFrames = double(winner.phaseSec) / double(frameSec);

    // ---- Отслеживание долей ----
    const std::vector<double> period =
        tempoCurve(pulseOdf, double(frameSec), globalPeriodFrames, cfg);
    const std::vector<size_t> beatFrames =
        trackBeats(pulseOdf, period, phaseFrames, globalPeriodFrames, double(cfg.tightness));

    if (beatFrames.size() < 2) {
        logf(cfg.verbose, "[takt] долей не найдено");
        return res;
    }

    // ---- Размер такта ----
    int meter = 0, meterOffset = 0; float meterStrength = 0.f;
    detectMeter(beatFrames, flux.band[BAND_BASS], meter, meterOffset, meterStrength);
    res.meter = meter;
    logf(cfg.verbose, "[takt] размер такта: %d (сильная доля со смещением %d, превышение баса %.2f)",
         meter, meterOffset, meterStrength);

    // ---- Сборка долей ----
    res.beats.reserve(beatFrames.size());
    for (size_t i = 0; i < beatFrames.size(); ++i) {
        size_t f = beatFrames[i];
        Beat b;
        b.timeSec = float(f) * frameSec + groupDelay;
        b.beatIndex = int32_t(i);

        if (meter > 0) {
            int pos = (int(i) - meterOffset) % meter;
            if (pos < 0) pos += meter;
            b.positionInBar = pos;
            b.barIndex = int32_t((int(i) - meterOffset) / meter);
        } else {
            b.positionInBar = 0;
            b.barIndex = -1;
        }

        // Сила доли — по басовому потоку рядом с ней.
        float strength = 0.f;
        for (long d = -2; d <= 2; ++d) {
            long k = long(f) + d;
            if (k >= 0 && k < long(frames))
                strength = std::max(strength, flux.band[BAND_BASS][size_t(k)]);
        }
        b.strength = strength;

        // Уверенность — насколько близко реальный удар к этой доле.
        float nearest = 1e9f;
        for (const Peak& p : peaks) {
            float d = std::fabs(p.timeSec - b.timeSec);
            if (d < nearest) nearest = d;
            if (p.timeSec > b.timeSec + 0.2f) break;
        }
        b.confidence = std::max(0.f, 1.f - nearest / cfg.gridTolerance);

        res.beats.push_back(b);
    }

    // Доли за пределами музыки.
    //
    // Динамическое программирование достраивает сетку назад и вперёд даже
    // там, где звука уже нет: во вступительной тишине и в затухающем хвосте.
    // Уверенность у таких долей честно нулевая, но её никто не смотрел, и
    // плитки появлялись до начала музыки. Срезаем только по краям —
    // внутри трека дыра в сетке хуже слабой доли.
    {
        size_t from = 0;
        while (from < res.beats.size() && res.beats[from].confidence <= kEdgeConfidence) ++from;
        size_t to = res.beats.size();
        while (to > from && res.beats[to - 1].confidence <= kEdgeConfidence) --to;

        if (to > from && (from > 0 || to < res.beats.size())) {
            logf(cfg.verbose, "[takt] срезано долей вне музыки: %zu в начале, %zu в конце",
                 from, res.beats.size() - to);
            std::vector<Beat> trimmed(res.beats.begin() + long(from), res.beats.begin() + long(to));
            for (size_t i = 0; i < trimmed.size(); ++i) trimmed[i].beatIndex = int32_t(i);
            res.beats.swap(trimmed);
        }
    }

    if (res.beats.size() < 2) {
        logf(cfg.verbose, "[takt] после отсечения долей не осталось");
        return res;
    }

    res.firstBeatSec = res.beats.front().timeSec;

    // ---- Итоговые числа ----
    std::vector<float> gaps;
    gaps.reserve(res.beats.size() - 1);
    for (size_t i = 1; i < res.beats.size(); ++i)
        gaps.push_back(res.beats[i].timeSec - res.beats[i - 1].timeSec);

    std::vector<float> sorted = gaps;
    float medianGap = medianOf(sorted);
    if (medianGap > 1e-4f) res.bpm = 60.f / medianGap;

    float minGap = *std::min_element(gaps.begin(), gaps.end());
    float maxGap = *std::max_element(gaps.begin(), gaps.end());
    res.tempoSpread = (minGap > 1e-4f && maxGap > 1e-4f)
                      ? std::fabs(60.f / minGap - 60.f / maxGap) : 0.f;

    // Уверенность: половина от качества сетки, половина от ровности интервалов.
    double dev = 0.0;
    for (float g : gaps) dev += std::fabs(double(g - medianGap));
    dev /= double(gaps.size());
    float regularity = float(std::max(0.0, 1.0 - dev / std::max(1e-4, double(medianGap)) * 3.0));
    res.confidence = 0.5f * winner.score + 0.5f * regularity;

    logf(cfg.verbose,
         "[takt] ИТОГ: %.2f BPM, первая доля %.3f с, долей %zu, уверенность %.2f, "
         "разброс темпа %.1f BPM, ровность %.2f",
         res.bpm, res.firstBeatSec, res.beats.size(), res.confidence,
         res.tempoSpread, regularity);

    // ---- Огибающая спектра для реактивного фона ----
    {
        const float stepSec = 1.f / std::max(1.f, cfg.envelopeHz);
        const size_t stepFrames = std::max<size_t>(1, size_t(stepSec / frameSec));
        const size_t points = frames / stepFrames;
        res.envelopeHz = cfg.envelopeHz;
        res.envelope.assign(points * 3, 0.f);

        float maxV[3] = { 1e-9f, 1e-9f, 1e-9f };
        for (size_t p = 0; p < points; ++p) {
            size_t a = p * stepFrames;
            size_t b = std::min(frames, a + stepFrames);
            float acc[3] = { 0.f, 0.f, 0.f };
            for (size_t k = a; k < b; ++k) {
                acc[0] += flux.bandEnergy[BAND_BASS][k] + flux.bandEnergy[BAND_LOWMID][k];
                acc[1] += flux.bandEnergy[BAND_MID][k];
                acc[2] += flux.bandEnergy[BAND_HIGH][k];
            }
            float n = float(std::max<size_t>(1, b - a));
            for (int j = 0; j < 3; ++j) {
                acc[j] /= n;
                res.envelope[p * 3 + size_t(j)] = acc[j];
                maxV[j] = std::max(maxV[j], acc[j]);
            }
        }
        // Полосы нормируем раздельно: бас всегда громче верха.
        for (size_t p = 0; p < points; ++p)
            for (int j = 0; j < 3; ++j)
                res.envelope[p * 3 + size_t(j)] =
                    std::min(1.f, res.envelope[p * 3 + size_t(j)] / maxV[j]);
    }

    return res;
}

} // namespace rg
