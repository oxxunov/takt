package com.oxxunov.voiceenhance.engine

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Результат анализа голоса для автоматической настройки. */
data class VoiceProfile(
    /** Октавные полосы, для которых посчитан спектр. */
    val bandFrequencies: DoubleArray,
    /** Средний спектр речи по полосам относительно полосы 1 кГц, дБ (NaN — полоса выше Найквиста). */
    val speechBandsRel: DoubleArray,
    /** Отношение речь/фон по каждой полосе, дБ. */
    val bandSnrDb: DoubleArray,
    /** Оценка времени реверберации, с (null — мало данных). */
    val t60Seconds: Double?,
    /** 95-й перцентиль доли 5–10 кГц в речи, дБ (ближе к 0 — резче свистящие). */
    val sibilanceDb: Double,
    /** Разброс громкости речи (p95 − p50), дБ. */
    val dynamicRangeDb: Double,
)

/**
 * Один проход по файлу: кадры ~40 мс с шагом 10 мс (FFT — JTransforms).
 * Считает средний спектр речи и фона по октавам, свистящие и слепую оценку реверберации
 * по скорости спада энергии после окончаний слогов (медиана наклонов → T60).
 */
object VoiceProfiler {
    val BANDS = doubleArrayOf(150.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 12000.0)
    private const val REF = 3 // индекс полосы 1 кГц

    fun profile(pcm: PcmFile, progress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): VoiceProfile {
        val sr = pcm.sampleRate
        val ch = pcm.channels
        val n = 1 shl (ln(0.04 * sr) / ln(2.0)).roundToInt()
        val hop = max(1, (0.01 * sr).roundToInt())
        val bins = n / 2 + 1
        val fft = DoubleFFT_1D(n.toLong())
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
        val binHz = sr.toDouble() / n
        val nyq = sr / 2.0

        fun binOf(hz: Double) = (hz / binHz).roundToInt().coerceIn(0, bins - 1)
        val bandLo = IntArray(BANDS.size) { binOf(BANDS[it] / sqrt(2.0)) }
        val bandHi = IntArray(BANDS.size) { binOf(min(BANDS[it] * sqrt(2.0), nyq * 0.95)) }
        val bandValid = BooleanArray(BANDS.size) { BANDS[it] * sqrt(2.0) < nyq * 1.05 && bandHi[it] > bandLo[it] }
        val voiceLo = binOf(300.0)
        val voiceHi = binOf(min(4000.0, nyq * 0.95))
        val sibLo = binOf(min(5000.0, nyq * 0.6))
        val sibHi = binOf(min(10000.0, nyq * 0.95))

        var voiceDb = FloatArray(4096)
        var sibRel = FloatArray(4096)
        var bandPow = FloatArray(4096 * BANDS.size)
        var frames = 0

        val ring = DoubleArray(n)
        var fill = 0
        val tmp = DoubleArray(n)
        val buf = Array(ch) { FloatArray(8192) }
        val total = pcm.frames

        PcmReader(pcm).use { r ->
            var done = 0L
            while (true) {
                if (isCancelled()) throw ProcessingCancelledException()
                val got = r.read(buf, 8192)
                if (got <= 0) break
                for (i in 0 until got) {
                    var m = 0.0
                    for (c in 0 until ch) m += buf[c][i]
                    ring[fill++] = m / ch
                    if (fill == n) {
                        for (k in 0 until n) tmp[k] = ring[k] * win[k]
                        fft.realForward(tmp)
                        if (frames == voiceDb.size) {
                            voiceDb = voiceDb.copyOf(frames * 2)
                            sibRel = sibRel.copyOf(frames * 2)
                            bandPow = bandPow.copyOf(frames * 2 * BANDS.size)
                        }
                        var full = 0.0
                        var voice = 0.0
                        var sib = 0.0
                        val bp = DoubleArray(BANDS.size)
                        for (b in 1 until bins - 1) {
                            val p = tmp[2 * b] * tmp[2 * b] + tmp[2 * b + 1] * tmp[2 * b + 1]
                            full += p
                            if (b in voiceLo..voiceHi) voice += p
                            if (b in sibLo..sibHi) sib += p
                            for (q in BANDS.indices) if (b in bandLo[q]..bandHi[q]) bp[q] += p
                        }
                        voiceDb[frames] = Db.fromPower(voice).toFloat()
                        sibRel[frames] = (Db.fromPower(sib) - Db.fromPower(full)).toFloat()
                        for (q in BANDS.indices) bandPow[frames * BANDS.size + q] = bp[q].toFloat()
                        frames++
                        System.arraycopy(ring, hop, ring, 0, n - hop)
                        fill = n - hop
                    }
                }
                done += got
                if (total > 0) progress((done.toDouble() / total).toFloat())
            }
        }

        val nb = BANDS.size
        if (frames < 20) {
            return VoiceProfile(BANDS.copyOf(), DoubleArray(nb) { 0.0 }, DoubleArray(nb) { 60.0 }, null, -30.0, 0.0)
        }
        val sorted = voiceDb.copyOf(frames).also { it.sort() }
        fun pct(q: Double) = sorted[((frames - 1) * q).roundToInt()].toDouble()
        val speechTop = pct(0.90)
        val noiseLevel = pct(0.10)
        val speechThr = speechTop - 12.0
        val noiseThr = pct(0.15)

        val sp = DoubleArray(nb)
        val np = DoubleArray(nb)
        var ns = 0
        var nn = 0
        val speechLevels = ArrayList<Float>()
        val sibs = ArrayList<Float>()
        for (f in 0 until frames) {
            val v = voiceDb[f]
            if (v >= speechThr) {
                ns++
                for (q in 0 until nb) sp[q] += bandPow[f * nb + q]
                speechLevels += v
                sibs += sibRel[f]
            } else if (v <= noiseThr) {
                nn++
                for (q in 0 until nb) np[q] += bandPow[f * nb + q]
            }
        }
        val ref = Db.fromPower(sp[REF] / max(1, ns))
        val rel = DoubleArray(nb) { q -> if (bandValid[q]) Db.fromPower(sp[q] / max(1, ns)) - ref else Double.NaN }
        val snr = DoubleArray(nb) { q ->
            if (!bandValid[q]) 0.0 else Db.fromPower(sp[q] / max(1, ns)) - Db.fromPower(np[q] / max(1, nn))
        }
        speechLevels.sort()
        sibs.sort()
        val dr = if (speechLevels.size > 4)
            (speechLevels[(speechLevels.size * 0.95).toInt().coerceAtMost(speechLevels.size - 1)] -
                speechLevels[speechLevels.size / 2]).toDouble() else 0.0
        val sib = if (sibs.isNotEmpty()) sibs[(sibs.size * 0.95).toInt().coerceAtMost(sibs.size - 1)].toDouble() else -30.0

        return VoiceProfile(
            bandFrequencies = BANDS.copyOf(),
            speechBandsRel = rel,
            bandSnrDb = snr,
            t60Seconds = estimateT60(voiceDb, frames, speechTop, noiseLevel, hop.toDouble() / sr),
            sibilanceDb = sib,
            dynamicRangeDb = dr,
        )
    }

    /** Медиана скоростей спада энергии после локальных максимумов речи → T60 = 60 / (дБ/с). */
    internal fun estimateT60(e: FloatArray, frames: Int, speechTop: Double, noise: Double, hopSec: Double): Double? {
        val slopes = ArrayList<Double>()
        var t = 2
        while (t < frames - 3) {
            val v = e[t]
            var isMax = v >= speechTop - 15.0
            if (isMax) for (k in t - 2..t + 2) if (e[k] > v) { isMax = false; break }
            if (isMax) {
                var j = t
                while (j + 1 < frames && j - t < 100 && e[j + 1] <= e[j] + 1.0f && e[j + 1] > noise + 3.0) j++
                val drop = (v - e[j]).toDouble()
                if (drop >= 10.0 && j - t >= 4) {
                    slopes += drop / ((j - t) * hopSec)
                    t = j
                    continue
                }
            }
            t++
        }
        if (slopes.size < 3) return null
        slopes.sort()
        val median = slopes[slopes.size / 2]
        return 60.0 / median
    }
}

/** Взвешивание по умолчанию: «сила» 0..1 → нелинейная кривая. */
internal fun curve(s: Double, gamma: Double): Double = s.coerceIn(0.0, 1.0).pow(gamma)

