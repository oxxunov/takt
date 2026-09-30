package com.oxxunov.voiceenhance.engine

import kotlin.math.max
import kotlin.math.roundToInt

/** Независимый блок обработки. Буфер планарный, обработка на месте. */
interface AudioProcessor {
    fun prepare(sampleRate: Int, channels: Int)
    fun process(buf: Array<FloatArray>, frames: Int)
    /** Задержка, вносимая блоком (в кадрах). Конвейер компенсирует её автоматически. */
    val latencyFrames: Int get() = 0
}

class GainProcessor(private val gainDb: Double) : AudioProcessor {
    private val g = Db.toLin(gainDb).toFloat()
    override fun prepare(sampleRate: Int, channels: Int) {}
    override fun process(buf: Array<FloatArray>, frames: Int) {
        if (gainDb == 0.0) return
        for (x in buf) for (i in 0 until frames) x[i] *= g
    }
}

class HighPassProcessor(private val s: HighPassSettings) : AudioProcessor {
    private var filters: Array<Array<Biquad>> = emptyArray()

    override fun prepare(sampleRate: Int, channels: Int) {
        val f = s.frequencyHz.coerceIn(10.0, sampleRate * 0.45)
        val qs = if (s.slope24) doubleArrayOf(0.5411961001461969, 1.3065629648763764) else doubleArrayOf(0.7071067811865476)
        filters = Array(channels) {
            Array(qs.size) { k -> Biquad(BiquadCoeffs.design(FilterType.HIGH_PASS, sampleRate, f, qs[k], 0.0)) }
        }
    }

    override fun process(buf: Array<FloatArray>, frames: Int) {
        for (c in filters.indices) {
            val x = buf[c]
            val fs = filters[c]
            for (i in 0 until frames) {
                var v = x[i].toDouble()
                for (f in fs) v = f.tick(v)
                x[i] = v.toFloat()
            }
        }
    }
}

class ParametricEqProcessor(private val s: EqSettings) : AudioProcessor {
    private var filters: Array<Array<Biquad>> = emptyArray()

    override fun prepare(sampleRate: Int, channels: Int) {
        val active = s.bands.filter { kotlin.math.abs(it.gainDb) >= 0.05 && it.frequencyHz < sampleRate * 0.45 }
        filters = Array(channels) {
            Array(active.size) { k ->
                val b = active[k]
                Biquad(BiquadCoeffs.design(FilterType.PEAK, sampleRate, b.frequencyHz, b.q, b.gainDb))
            }
        }
    }

    override fun process(buf: Array<FloatArray>, frames: Int) {
        for (c in filters.indices) {
            val fs = filters[c]
            if (fs.isEmpty()) continue
            val x = buf[c]
            for (i in 0 until frames) {
                var v = x[i].toDouble()
                for (f in fs) v = f.tick(v)
                x[i] = v.toFloat()
            }
        }
    }
}

/**
 * Компрессор feed-forward: RMS-детектор (10 мс), мягкое колено, сглаживание усиления attack/release,
 * каналы связаны (одно усиление на все каналы — стереокартина не плывёт).
 */
class CompressorProcessor(
    private val s: CompressorSettings,
    /** Уровень речи из анализа (дБ RMS); используется в режиме Auto. */
    private val speechLevelDb: Double?,
) : AudioProcessor {
    private var threshold = 0.0
    private var ratio = 1.0
    private var knee = 0.0
    private var makeup = 0.0
    private var rmsCoef = 0.0
    private var attCoef = 0.0
    private var relCoef = 0.0
    private var ms = 0.0
    private var grEnv = 0.0

    override fun prepare(sampleRate: Int, channels: Int) {
        val auto = s.auto
        threshold = if (auto && speechLevelDb != null && speechLevelDb > -80.0) speechLevelDb - 8.0 else s.thresholdDb
        ratio = (if (auto) 3.0 else s.ratio).coerceAtLeast(1.0)
        knee = (if (auto) 6.0 else s.kneeDb).coerceAtLeast(0.0)
        makeup = if (auto) 0.0 else s.makeupDb
        val attack = if (auto) 8.0 else s.attackMs
        val release = if (auto) 150.0 else s.releaseMs
        rmsCoef = smoothingCoef(10.0, sampleRate)
        attCoef = smoothingCoef(attack, sampleRate)
        relCoef = smoothingCoef(release, sampleRate)
        ms = 0.0
        grEnv = 0.0
    }

    fun gainComputer(levelDb: Double): Double {
        val over = levelDb - threshold
        val slope = 1.0 / ratio - 1.0
        return when {
            knee > 0.0 && 2.0 * kotlin.math.abs(over) <= knee -> slope * (over + knee / 2.0) * (over + knee / 2.0) / (2.0 * knee)
            over > 0.0 -> slope * over
            else -> 0.0
        }
    }

    override fun process(buf: Array<FloatArray>, frames: Int) {
        val ch = buf.size
        for (i in 0 until frames) {
            var p = 0.0
            for (c in 0 until ch) {
                val v = buf[c][i].toDouble()
                val q = v * v
                if (q > p) p = q
            }
            ms = rmsCoef * ms + (1.0 - rmsCoef) * p
            val target = gainComputer(Db.fromPower(ms))
            grEnv = if (target < grEnv) attCoef * grEnv + (1.0 - attCoef) * target
            else relCoef * grEnv + (1.0 - relCoef) * target
            val g = Db.toLin(grEnv + makeup).toFloat()
            for (c in 0 until ch) buf[c][i] *= g
        }
    }
}

/**
 * Де-эссер с разделением полос Linkwitz–Riley 4-го порядка: низ и верх в фазе, поэтому ослабляется
 * только верхняя полоса и только в момент свистящего звука. Детектор сравнивает энергию верхней
 * полосы с широкополосной — сибилянт определяется по доле «верха», а не по абсолютной громкости.
 */
class DeEsserProcessor(private val s: DeEsserSettings) : AudioProcessor {
    private var lp1: Array<Biquad> = emptyArray()
    private var lp2: Array<Biquad> = emptyArray()
    private var hp1: Array<Biquad> = emptyArray()
    private var hp2: Array<Biquad> = emptyArray()
    private var lo = DoubleArray(0)
    private var hi = DoubleArray(0)
    private var envHi = 0.0
    private var envFull = 0.0
    private var grEnv = 0.0
    private var envAtt = 0.0
    private var envRel = 0.0
    private var grAtt = 0.0
    private var grRel = 0.0
    private var thresholdRel = 0.0
    private var maxRed = 0.0

    override fun prepare(sampleRate: Int, channels: Int) {
        val f = s.frequencyHz.coerceIn(2000.0, sampleRate * 0.4)
        val q = 0.7071067811865476
        lp1 = Array(channels) { Biquad(BiquadCoeffs.design(FilterType.LOW_PASS, sampleRate, f, q, 0.0)) }
        lp2 = Array(channels) { Biquad(BiquadCoeffs.design(FilterType.LOW_PASS, sampleRate, f, q, 0.0)) }
        hp1 = Array(channels) { Biquad(BiquadCoeffs.design(FilterType.HIGH_PASS, sampleRate, f, q, 0.0)) }
        hp2 = Array(channels) { Biquad(BiquadCoeffs.design(FilterType.HIGH_PASS, sampleRate, f, q, 0.0)) }
        lo = DoubleArray(channels)
        hi = DoubleArray(channels)
        val a = (s.amount / 100.0).coerceIn(0.0, 1.0)
        thresholdRel = -4.0 - 10.0 * a
        maxRed = 14.0 * a
        envAtt = smoothingCoef(0.5, sampleRate)
        envRel = smoothingCoef(30.0, sampleRate)
        grAtt = smoothingCoef(1.0, sampleRate)
        grRel = smoothingCoef(60.0, sampleRate)
        envHi = 0.0; envFull = 0.0; grEnv = 0.0
    }

    private fun follow(env: Double, p: Double): Double =
        if (p > env) envAtt * env + (1 - envAtt) * p else envRel * env + (1 - envRel) * p

    override fun process(buf: Array<FloatArray>, frames: Int) {
        val ch = buf.size
        for (i in 0 until frames) {
            var pHi = 0.0
            var pFull = 0.0
            for (c in 0 until ch) {
                val x = buf[c][i].toDouble()
                val l = lp2[c].tick(lp1[c].tick(x))
                val h = hp2[c].tick(hp1[c].tick(x))
                lo[c] = l; hi[c] = h
                if (h * h > pHi) pHi = h * h
                if (x * x > pFull) pFull = x * x
            }
            envHi = follow(envHi, pHi)
            envFull = follow(envFull, pFull)
            val hiDb = Db.fromPower(envHi)
            val fullDb = Db.fromPower(envFull)
            val target = if (hiDb > -55.0) ((hiDb - fullDb - thresholdRel) * 1.5).coerceIn(0.0, maxRed) else 0.0
            grEnv = if (target > grEnv) grAtt * grEnv + (1 - grAtt) * target else grRel * grEnv + (1 - grRel) * target
            val g = Db.toLin(-grEnv)
            for (c in 0 until ch) buf[c][i] = (lo[c] + g * hi[c]).toFloat()
        }
    }
}

/**
 * True-peak лимитер с look-ahead.
 * Требуемое усиление считается по интерполированному (4x) пику, затем скользящий минимум на окне
 * look-ahead и усреднение тем же окном — это математически гарантирует, что к моменту пика усиление
 * уже опущено до нужного, без щелчков. Звук задерживается на look-ahead + задержку интерполятора.
 */
class LimiterProcessor(private val s: LimiterSettings) : AudioProcessor {
    private var lookahead = 1
    private var delay = 0
    private var ceil = 1.0
    private var outGain = 1.0
    private var relCoef = 0.0
    private var detectors: Array<TruePeakDetector> = emptyArray()
    private var delayLines: Array<DoubleArray> = emptyArray()
    private var dPos = 0

    // монотонная очередь для скользящего минимума
    private var qTime = LongArray(0)
    private var qVal = DoubleArray(0)
    private var qHead = 0
    private var qSize = 0

    private var avgRing = DoubleArray(0)
    private var avgPos = 0
    private var avgSum = 0.0
    private var m2 = 1.0
    private var t = 0L

    override val latencyFrames: Int get() = delay

    override fun prepare(sampleRate: Int, channels: Int) {
        lookahead = max(1, (s.lookaheadMs * sampleRate / 1000.0).roundToInt())
        delay = TruePeakKernel.DELAY + lookahead - 1
        // внутренний запас 0.1 дБ на погрешность интерполяции
        ceil = Db.toLin(s.ceilingDbTp.coerceAtMost(0.0) - 0.1)
        outGain = Db.toLin(s.outputLevelDb.coerceAtMost(0.0))
        relCoef = smoothingCoef(s.releaseMs, sampleRate)
        detectors = Array(channels) { TruePeakDetector() }
        delayLines = Array(channels) { DoubleArray(delay) }
        dPos = 0
        qTime = LongArray(lookahead + 1)
        qVal = DoubleArray(lookahead + 1)
        qHead = 0; qSize = 0
        avgRing = DoubleArray(lookahead) { 1.0 }
        avgPos = 0
        avgSum = lookahead.toDouble()
        m2 = 1.0
        t = 0L
    }

    override fun process(buf: Array<FloatArray>, frames: Int) {
        val ch = buf.size
        val cap = qTime.size
        for (i in 0 until frames) {
            var tp = 0.0
            for (c in 0 until ch) {
                val p = detectors[c].push(buf[c][i].toDouble())
                if (p > tp) tp = p
            }
            val req = if (tp > ceil) ceil / tp else 1.0

            // скользящий минимум req за последние lookahead отсчётов
            while (qSize > 0 && qVal[(qHead + qSize - 1) % cap] >= req) qSize--
            val tail = (qHead + qSize) % cap
            qTime[tail] = t; qVal[tail] = req; qSize++
            while (qTime[qHead] <= t - lookahead) {
                qHead = (qHead + 1) % cap; qSize--
            }
            val m = qVal[qHead]

            m2 = if (m < m2) m else m2 + (m - m2) * (1.0 - relCoef)

            avgSum += m2 - avgRing[avgPos]
            avgRing[avgPos] = m2
            avgPos++
            if (avgPos == lookahead) {
                avgPos = 0
                avgSum = avgRing.sum() // убираем накопленную ошибку округления
            }
            val a = (avgSum / lookahead).coerceAtMost(1.0)

            for (c in 0 until ch) {
                val d = delayLines[c]
                val delayed = d[dPos]
                d[dPos] = buf[c][i].toDouble()
                val y = (delayed * a).coerceIn(-ceil, ceil) * outGain
                buf[c][i] = y.toFloat()
            }
            dPos++
            if (dPos == delay) dPos = 0
            t++
        }
    }
}
