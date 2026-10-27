package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MuteMixTests {
    @Test
    fun planPadsAndMerges() {
        val z = listOf(MuteZone(1000, 2000, 0f), MuteZone(2500, 3000, 0f), MuteZone(9000, 9500, 0f))
        val p = MuteMixer.plan(z, 400, 9700)
        assertEquals(listOf(MuteSpan(600, 3400), MuteSpan(8600, 9700)), p)
    }

    @Test
    fun weightRampsOutsideZone() {
        val z = listOf(MuteZone(1000, 2000, 0.2f))
        assertEquals(0f, MuteMixer.weight(800, z, 100))
        assertEquals(0.8f, MuteMixer.weight(1000, z, 100), 1e-6f)
        assertEquals(0.8f, MuteMixer.weight(1999, z, 100), 1e-6f)
        assertEquals(0f, MuteMixer.weight(2100, z, 100))
        val mid = MuteMixer.weight(950, z, 100)
        assertTrue(mid > 0.3f && mid < 0.5f)
    }

    @Test
    fun mixReplacesOnlyInsideZones() {
        val dir = TestSignals.tempDir()
        val sr = 8000
        val n = 8000
        val orig = Array(2) { c -> FloatArray(n) { 0.5f + c * 0.1f } }
        val zones = listOf(MuteZone(3000, 4000, 0f), MuteZone(6000, 6500, 0.25f))
        val spans = MuteMixer.plan(zones, 400, n.toLong())
        val bgs = spans.mapIndexed { k, sp ->
            val bg = Array(2) { FloatArray(sp.frames.toInt()) { 0.1f } }
            sp to TestSignals.toPcm(dir, "bg$k.f32", sr, bg)
        }
        val o = TestSignals.toPcm(dir, "o.f32", sr, orig)
        val out = MuteMixer.render(o, bgs, zones, 80, File(dir, "m.f32"))
        val y = TestSignals.readAll(out)
        assertEquals(n, y[0].size)
        assertEquals(0.5f, y[0][1000], 1e-6f)
        assertEquals(0.1f, y[0][3500], 1e-6f)
        assertEquals(0.1f, y[1][3500], 1e-6f)
        // оставить 25 % голоса: 0.1 + 0.25·(0.5 − 0.1)
        assertEquals(0.2f, y[0][6200], 1e-6f)
        assertEquals(0.5f, y[0][5000], 1e-6f)
        // плавный край без скачков
        for (i in 2900 until 3100) assertTrue(abs(y[0][i] - y[0][i - 1]) < 0.02f)
    }

    @Test
    fun advanceShiftsAndKeepsLength() {
        val dir = TestSignals.tempDir()
        val orig = arrayOf(FloatArray(1000) { it.toFloat() })
        val o = TestSignals.toPcm(dir, "o.f32", 8000, orig)
        val buf = arrayOf(FloatArray(1000))
        MuteMixer.open(o, emptyList(), emptyList(), 0, advance = 10).use { src ->
            var got = 0
            while (true) {
                val tmp = arrayOf(FloatArray(300))
                val k = src.read(tmp, 300)
                if (k <= 0) break
                System.arraycopy(tmp[0], 0, buf[0], got, k)
                got += k
            }
            assertEquals(1000, got)
        }
        assertEquals(10f, buf[0][0])
        assertEquals(0f, buf[0][999])
    }
}
