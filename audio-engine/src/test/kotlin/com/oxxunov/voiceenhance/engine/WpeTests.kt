package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WpeTests {

    @Test
    fun zeroStrengthIsTransparent() {
        val dir = TestSignals.tempDir()
        for ((sr, ch) in listOf(48000 to 1, 44100 to 2)) {
            val x = TestSignals.speechLike(sr, 2.5, ch, 0.5)
            val input = TestSignals.toPcm(dir, "t$sr.f32", sr, x)
            val out = WpeDereverb(0.0, blockSeconds = 1.0).run(input, File(dir, "o$sr.f32"), {}, { false })
            assertEquals(input.frames, out.frames)
            assertEquals(ch, out.channels)
            val y = TestSignals.readAll(out)
            var m = 0.0
            for (c in 0 until ch) for (i in x[c].indices) m = maxOf(m, abs((x[c][i] - y[c][i]).toDouble()))
            assertTrue(m < 1e-4, "WPE при 0 % должен быть прозрачным, ошибка $m")
        }
    }

    @Test
    fun reducesLateReverb() {
        val sr = 16000
        val n = sr * 8
        val rnd = Random(11)
        val gains = DoubleArray(n / 1200 + 1) { 0.02 + 0.98 * rnd.nextDouble() }
        val dry = DoubleArray(n) { gaussian(rnd) * gains[it / 1200] }
        val len = sr
        val rir = DoubleArray(len) { i -> gaussian(rnd) * exp(-6.9 * i / sr / 0.6) * 0.05 }
        rir[0] = 1.0
        val cut = (0.05 * sr).toInt()
        val wet = DoubleArray(n)
        val early = DoubleArray(n)
        // свёртка (прямая, короткий тест)
        for (i in 0 until n) {
            val di = dry[i]
            if (di == 0.0) continue
            val lim = minOf(len, n - i)
            for (j in 0 until lim) {
                val v = di * rir[j]
                wet[i + j] += v
                if (j < cut) early[i + j] += v
            }
        }
        var peak = 0.0
        for (v in wet) peak = maxOf(peak, abs(v))
        val sc = 1.0 / (peak * 1.1)
        val x = arrayOf(FloatArray(n) { (wet[it] * sc).toFloat() })
        val dir = TestSignals.tempDir()
        val input = TestSignals.toPcm(dir, "rev.f32", sr, x)
        val out = WpeDereverb(1.0).run(input, File(dir, "rev_out.f32"), {}, { false })
        val y = TestSignals.readAll(out)[0]
        var lateE = 0.0
        var errE = 0.0
        for (i in sr until n) {
            val e = early[i] * sc
            val l = x[0][i] - e
            lateE += l * l
            val r = y[i] - e
            errE += r * r
        }
        val gain = 10 * log10(lateE / errE)
        println("WPE: подавление поздней реверберации = ${"%.2f".format(gain)} дБ")
        assertTrue(gain > 1.0, "WPE должен подавлять позднюю реверберацию, получено $gain дБ")
        TestSignals.assertFinite(arrayOf(y))
    }

    @Test
    fun complexSolverIsAccurate() {
        val n = 6
        val rnd = Random(3)
        val mr = DoubleArray(n * n) { rnd.nextDouble() - 0.5 }
        val mi = DoubleArray(n * n) { rnd.nextDouble() - 0.5 }
        // A = M·Mᴴ + I (эрмитова, положительно определённая)
        val ar = DoubleArray(n * n)
        val ai = DoubleArray(n * n)
        for (a in 0 until n) for (b in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (c in 0 until n) {
                val xr = mr[a * n + c]; val xi = mi[a * n + c]
                val yr = mr[b * n + c]; val yi = -mi[b * n + c]
                sr += xr * yr - xi * yi
                si += xr * yi + xi * yr
            }
            ar[a * n + b] = sr + if (a == b) 1.0 else 0.0
            ai[a * n + b] = si
        }
        val br = DoubleArray(n) { rnd.nextDouble() }
        val bi = DoubleArray(n) { rnd.nextDouble() }
        val sr = br.copyOf()
        val si = bi.copyOf()
        assertTrue(solveComplex(ar.copyOf(), ai.copyOf(), sr, si, n))
        for (a in 0 until n) {
            var rr = 0.0
            var ri = 0.0
            for (b in 0 until n) {
                rr += ar[a * n + b] * sr[b] - ai[a * n + b] * si[b]
                ri += ar[a * n + b] * si[b] + ai[a * n + b] * sr[b]
            }
            assertEquals(br[a], rr, 1e-9)
            assertEquals(bi[a], ri, 1e-9)
        }
    }

    private fun gaussian(r: Random): Double {
        var s = 0.0
        repeat(12) { s += r.nextDouble() }
        return s - 6.0
    }
}
