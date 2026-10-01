package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StemAlignTests {
    private val sr = 44100

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun case(shift: Int) {
        val dir = TestSignals.tempDir()
        val n = sr * 20
        val speech = TestSignals.speechLike(sr, 20.0, 2, 0.3)
        val rnd = Random(3)
        val music = Array(2) { FloatArray(n) { ((rnd.nextDouble() - 0.5) * 0.1).toFloat() } }
        val mix = Array(2) { c -> FloatArray(n) { speech[c][it] + music[c][it] } }
        // «речь от модели», сдвинутая на shift сэмплов (shift>0 — опаздывает)
        val est = Array(2) { c -> FloatArray(n) { i -> val j = i - shift; if (j in 0 until n) speech[c][j] else 0f } }
        val pm = TestSignals.toPcm(dir, "m.f32", sr, mix)
        val ps = TestSignals.toPcm(dir, "s.f32", sr, est)

        val r = StemAlign.measure(pm, ps)
        assertEquals(shift, r.lag, "сдвиг")
        assertTrue(r.corr > 0.5, "корреляция ${r.corr}")

        val fixed = StemAlign.shift(ps, r.lag, File(dir, "f.f32"))
        assertEquals(ps.frames, fixed.frames)
        val bg = TestSignals.readAll(PcmMath.subtract(pm, fixed, 1f, File(dir, "b.f32")))[0]
        val from = 5000; val to = n - 5000
        // после выравнивания остаётся только «музыка»
        assertTrue(rms(bg, from, to) < rms(music[0], from, to) * 1.05, "остаток речи")
    }

    @Test fun late() = case(37)
    @Test fun early() = case(-53)
    @Test fun aligned() = case(0)
}
