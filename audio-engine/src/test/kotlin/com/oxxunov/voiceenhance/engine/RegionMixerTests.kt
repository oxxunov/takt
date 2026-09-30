package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionMixerTests {
    private val sr = 48000

    private fun setup(dir: File): List<PcmFile> {
        val n = sr * 2
        val speech = arrayOf(FloatArray(n) { 0.2f })
        val music = arrayOf(FloatArray(n) { 0.3f })
        val fx = arrayOf(FloatArray(n) { 0.1f })
        val orig = arrayOf(FloatArray(n) { 0.6f })
        val base = arrayOf(FloatArray(n) { 0.4f }) // оригинал − речь
        return listOf(
            TestSignals.toPcm(dir, "o.f32", sr, orig), TestSignals.toPcm(dir, "b.f32", sr, base),
            TestSignals.toPcm(dir, "s.f32", sr, speech), TestSignals.toPcm(dir, "m.f32", sr, music),
            TestSignals.toPcm(dir, "e.f32", sr, fx),
        )
    }

    @Test
    fun noRegionsGivesBase() {
        val dir = TestSignals.tempDir()
        val (o, b, s, m, e) = setup(dir)
        val y = TestSignals.readAll(RegionMixer.render(o, b, listOf(s, m, e), emptyList(), File(dir, "y.f32")))[0]
        assertTrue(y.all { abs(it - 0.4f) < 1e-6 })
    }

    @Test
    fun regionAppliesGainsWithSmoothEdges() {
        val dir = TestSignals.tempDir()
        val (o, b, s, m, e) = setup(dir)
        // в середине: речь убрать, эффекты (например, шёпот) тоже убрать, музыку оставить → 0.6 − 0.2 − 0.1 = 0.3
        val reg = MixRegion(sr / 2L, sr * 3L / 2, floatArrayOf(0f, 1f, 0f))
        val y = TestSignals.readAll(RegionMixer.render(o, b, listOf(s, m, e), listOf(reg), File(dir, "y.f32")))[0]
        assertEquals(2 * sr, y.size)
        assertEquals(0.4f, y[sr / 4], 1e-6f)
        assertEquals(0.3f, y[sr], 1e-6f)
        assertEquals(0.4f, y[sr * 7 / 4], 1e-6f)
        for (i in 1 until y.size) assertTrue(abs(y[i] - y[i - 1]) < 0.001f, "скачок на $i")
    }

    @Test
    fun restoreOriginalRegion() {
        val dir = TestSignals.tempDir()
        val (o, b, s, m, e) = setup(dir)
        val reg = MixRegion(0, sr * 2L, floatArrayOf(1f, 1f, 1f))
        val y = TestSignals.readAll(RegionMixer.render(o, b, listOf(s, m, e), listOf(reg), File(dir, "y.f32")))[0]
        assertEquals(0.6f, y[sr], 1e-6f)
    }
}
