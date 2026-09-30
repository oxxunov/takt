package com.oxxunov.voiceenhance.engine

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Интегральная громкость по ITU-R BS.1770-4 / EBU R128:
 * K-weighting, блоки 400 мс с перекрытием 75 %, абсолютный гейт −70 LUFS, относительный −10 LU.
 * Работает потоково: хранит только энергии 100-мс сегментов.
 */
class LoudnessMeter(sampleRate: Int, private val channels: Int) {
    private val shelf = Array(channels) { Biquad(BiquadCoeffs.kWeightShelf(sampleRate)) }
    private val hpf = Array(channels) { Biquad(BiquadCoeffs.kWeightHighPass(sampleRate)) }
    private val segLen = max(1, (sampleRate * 0.1).roundToInt())
    private var segAcc = 0.0
    private var segCount = 0
    private var segs = DoubleArray(1024)
    private var nSegs = 0

    fun process(buf: Array<FloatArray>, offset: Int, frames: Int) {
        for (i in offset until offset + frames) {
            var e = 0.0
            for (c in 0 until channels) {
                val v = hpf[c].tick(shelf[c].tick(buf[c][i].toDouble()))
                e += v * v // вес каналов L/R (и моно) = 1.0
            }
            segAcc += e
            segCount++
            if (segCount == segLen) {
                if (nSegs == segs.size) segs = segs.copyOf(segs.size * 2)
                segs[nSegs++] = segAcc / segLen
                segAcc = 0.0
                segCount = 0
            }
        }
    }

    /** Интегральная громкость, LUFS. −∞, если материала меньше 400 мс или всё ниже −70 LUFS. */
    fun integratedLufs(): Double {
        if (nSegs < 4) return Double.NEGATIVE_INFINITY
        val nb = nSegs - 3
        val blocks = DoubleArray(nb) { j -> (segs[j] + segs[j + 1] + segs[j + 2] + segs[j + 3]) / 4.0 }
        val absThr = energyOf(-70.0)
        var sum = 0.0
        var n = 0
        for (b in blocks) if (b > absThr) { sum += b; n++ }
        if (n == 0) return Double.NEGATIVE_INFINITY
        val relThr = sum / n * 10.0.pow(-1.0)
        sum = 0.0; n = 0
        for (b in blocks) if (b > absThr && b > relThr) { sum += b; n++ }
        if (n == 0) return Double.NEGATIVE_INFINITY
        return -0.691 + 10.0 * log10(sum / n)
    }

    private fun energyOf(lufs: Double): Double = 10.0.pow((lufs + 0.691) / 10.0)
}
