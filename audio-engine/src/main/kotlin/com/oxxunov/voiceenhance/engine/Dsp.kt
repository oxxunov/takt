package com.oxxunov.voiceenhance.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object Db {
    private val LN10_20 = ln(10.0) / 20.0
    fun toLin(db: Double): Double = exp(db * LN10_20)
    fun fromLin(x: Double): Double = if (x <= 1e-12) -240.0 else 20.0 * log10(x)
    fun fromPower(p: Double): Double = if (p <= 1e-24) -240.0 else 10.0 * log10(p)
}

/** Коэффициент одно-полюсного сглаживания для постоянной времени [ms]. */
fun smoothingCoef(ms: Double, sampleRate: Int): Double {
    val t = ms.coerceAtLeast(0.01) / 1000.0 * sampleRate
    return exp(-1.0 / t)
}

enum class FilterType { LOW_PASS, HIGH_PASS, PEAK, LOW_SHELF, HIGH_SHELF }

class BiquadCoeffs(
    val b0: Double, val b1: Double, val b2: Double,
    val a1: Double, val a2: Double,
) {
    companion object {
        /** RBJ Audio EQ Cookbook. */
        fun design(type: FilterType, sampleRate: Int, freq: Double, q: Double, gainDb: Double): BiquadCoeffs {
            val f = freq.coerceIn(1.0, sampleRate * 0.49)
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f / sampleRate
            val cs = cos(w0)
            val sn = sin(w0)
            val alpha = sn / (2.0 * q.coerceAtLeast(0.05))
            val b0: Double; val b1: Double; val b2: Double
            val a0: Double; val a1: Double; val a2: Double
            when (type) {
                FilterType.LOW_PASS -> {
                    b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = (1 - cs) / 2
                    a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha
                }
                FilterType.HIGH_PASS -> {
                    b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = (1 + cs) / 2
                    a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha
                }
                FilterType.PEAK -> {
                    b0 = 1 + alpha * a; b1 = -2 * cs; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cs; a2 = 1 - alpha / a
                }
                FilterType.LOW_SHELF -> {
                    val sq = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cs + sq)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cs)
                    b2 = a * ((a + 1) - (a - 1) * cs - sq)
                    a0 = (a + 1) + (a - 1) * cs + sq
                    a1 = -2 * ((a - 1) + (a + 1) * cs)
                    a2 = (a + 1) + (a - 1) * cs - sq
                }
                FilterType.HIGH_SHELF -> {
                    val sq = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cs + sq)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cs)
                    b2 = a * ((a + 1) + (a - 1) * cs - sq)
                    a0 = (a + 1) - (a - 1) * cs + sq
                    a1 = 2 * ((a - 1) - (a + 1) * cs)
                    a2 = (a + 1) - (a - 1) * cs - sq
                }
            }
            return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        /** K-weighting ITU-R BS.1770-4, ступень 1 (high shelf) — точные формулы для любой частоты дискретизации. */
        fun kWeightShelf(sampleRate: Int): BiquadCoeffs {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(g / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return BiquadCoeffs(
                (vh + vb * k / q + k * k) / a0,
                2.0 * (k * k - vh) / a0,
                (vh - vb * k / q + k * k) / a0,
                2.0 * (k * k - 1.0) / a0,
                (1.0 - k / q + k * k) / a0,
            )
        }

        /** K-weighting ITU-R BS.1770-4, ступень 2 (RLB high pass). */
        fun kWeightHighPass(sampleRate: Int): BiquadCoeffs {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val d = 1.0 + k / q + k * k
            return BiquadCoeffs(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / d, (1.0 - k / q + k * k) / d)
        }
    }
}

/** Transposed Direct Form II, двойная точность. */
class Biquad(private val c: BiquadCoeffs) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun tick(x: Double): Double {
        val y = c.b0 * x + z1
        z1 = c.b1 * x - c.a1 * y + z2
        z2 = c.b2 * x - c.a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0; z2 = 0.0
    }
}

/**
 * Интерполятор для true peak: 4-кратная передискретизация windowed-sinc (Blackman, 16 отводов на фазу).
 * Оценивает пики между сэмплами, как требует BS.1770-4.
 */
object TruePeakKernel {
    const val TAPS = 16
    const val DELAY = 8

    /** phases[k] — фильтр для позиции c + (k+1)/4; индекс m соответствует x[c + m - 7]. */
    val phases: Array<DoubleArray> = Array(3) { k ->
        val t0 = (k + 1) / 4.0
        val h = DoubleArray(TAPS) { m ->
            val j = m - 7
            val t = t0 - j
            val sinc = sin(PI * t) / (PI * t)
            val w = 0.42 + 0.5 * cos(PI * t / DELAY) + 0.08 * cos(2.0 * PI * t / DELAY)
            sinc * w
        }
        val s = h.sum()
        for (i in h.indices) h[i] /= s
        h
    }
}

/** Потоковый детектор true peak для одного канала. Задержка — [TruePeakKernel.DELAY] сэмплов. */
class TruePeakDetector {
    private val ring = DoubleArray(TruePeakKernel.TAPS)
    private var pos = 0

    /** Добавляет x[n], возвращает оценку пика на интервале [n-8, n-7). */
    fun push(x: Double): Double {
        ring[pos] = x
        pos = (pos + 1) and (TruePeakKernel.TAPS - 1)
        var peak = kotlin.math.abs(ring[(pos + 7) and 15])
        for (k in 0 until 3) {
            val h = TruePeakKernel.phases[k]
            var acc = 0.0
            for (m in 0 until TruePeakKernel.TAPS) acc += ring[(pos + m) and 15] * h[m]
            val a = kotlin.math.abs(acc)
            if (a > peak) peak = a
        }
        return peak
    }
}
