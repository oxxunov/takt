package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcmMathTests {
    @Test
    fun subtractRemovesComponentExactly() {
        val dir = TestSignals.tempDir()
        val sr = 48000
        val music = TestSignals.sine(sr, 220.0, 0.3, 1.0, 2)
        val speech = TestSignals.sine(sr, 700.0, 0.2, 1.0, 2)
        val mix = Array(2) { c -> FloatArray(sr) { music[c][it] + speech[c][it] } }
        val out = PcmMath.subtract(
            TestSignals.toPcm(dir, "mix.f32", sr, mix), TestSignals.toPcm(dir, "sp.f32", sr, speech), 1f, File(dir, "o.f32")
        )
        val y = TestSignals.readAll(out)
        var e = 0.0
        for (c in 0 until 2) for (i in 0 until sr) e = maxOf(e, kotlin.math.abs((y[c][i] - music[c][i]).toDouble()))
        assertTrue(e < 1e-6)
        assertEquals(sr.toLong(), out.frames)
    }

    @Test
    fun advanceKeepsLength() {
        val dir = TestSignals.tempDir()
        val x = arrayOf(FloatArray(1000) { it.toFloat() })
        val out = PcmMath.advance(TestSignals.toPcm(dir, "a.f32", 48000, x), 100, File(dir, "b.f32"))
        val y = TestSignals.readAll(out)[0]
        assertEquals(1000, y.size)
        assertEquals(100f, y[0])
        assertEquals(0f, y[999])
    }

    @Test
    fun containerRules() {
        assertEquals(ContainerRules.Container.MP4, ContainerRules.plan("video/avc", 26)!!.container)
        assertEquals(ContainerRules.Container.WEBM, ContainerRules.plan("video/x-vnd.on2.vp9", 30)!!.container)
        assertNull(ContainerRules.plan("video/x-vnd.on2.vp9", 28))
        assertNull(ContainerRules.plan("video/av01", 33))
        assertNull(ContainerRules.plan("video/unknown", 35))
    }
}
