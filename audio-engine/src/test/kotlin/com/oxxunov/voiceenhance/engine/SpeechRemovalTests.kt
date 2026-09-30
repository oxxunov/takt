package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechRemovalTests {
    private val sr = 48000

    @Test
    fun highIsTransparentWithoutSpeech() {
        val dir = TestSignals.tempDir()
        val mix = TestSignals.speechLike(sr, 2.0, 2, 0.4)
        val zero = Array(2) { FloatArray(mix[0].size) }
        val out = SpeechRemoval.apply(
            TestSignals.toPcm(dir, "m.f32", sr, mix), TestSignals.toPcm(dir, "z.f32", sr, zero),
            RemovalStrength.HIGH, File(dir, "o.f32"),
        )
        val y = TestSignals.readAll(out)
        assertEquals(mix[0].size, y[0].size)
        var e = 0.0
        for (c in 0 until 2) for (i in mix[c].indices) e = maxOf(e, abs((mix[c][i] - y[c][i]).toDouble()))
        assertTrue(e < 1e-4, "без речи HIGH не должен менять звук, ошибка $e")
    }

    @Test
    fun strengthOrderIsMonotonic() {
        val dir = TestSignals.tempDir()
        val music = TestSignals.sine(sr, 220.0, 0.3, 2.0)
        val speechTrue = TestSignals.sine(sr, 1500.0, 0.3, 2.0)
        val mix = arrayOf(FloatArray(music[0].size) { music[0][it] + speechTrue[0][it] })
        // оценка речи с ошибкой 20 % — как у реальной модели
        val est = arrayOf(FloatArray(music[0].size) { speechTrue[0][it] * 0.8f })
        val m = TestSignals.toPcm(dir, "mix.f32", sr, mix)
        val s = TestSignals.toPcm(dir, "est.f32", sr, est)
        fun speechLeft(st: RemovalStrength): Double {
            val y = TestSignals.readAll(SpeechRemoval.apply(m, s, st, File(dir, "o_$st.f32")))[0]
            var e = 0.0
            for (i in sr / 2 until sr * 3 / 2) { val d = (y[i] - music[0][i]).toDouble(); e += d * d }
            return e
        }
        val low = speechLeft(RemovalStrength.LOW)
        val med = speechLeft(RemovalStrength.MEDIUM)
        val high = speechLeft(RemovalStrength.HIGH)
        println("остаток речи: low=$low med=$med high=$high")
        assertTrue(low > med && med > high)
    }
}
