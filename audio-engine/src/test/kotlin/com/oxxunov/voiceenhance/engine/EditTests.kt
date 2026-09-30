package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditTests {
    private val sr = 48000

    @Test
    fun trimKeepsExactRange() {
        val dir = TestSignals.tempDir()
        val x = Array(2) { c -> FloatArray(sr) { (it + c * 0.5f) / sr } }
        val pcm = TestSignals.toPcm(dir, "a.f32", sr, x)
        val t = AudioEdits.trim(pcm, 1000, 21000, File(dir, "t.f32"))
        assertEquals(20000L, t.frames)
        assertEquals(2, t.channels)
        val y = TestSignals.readAll(t)
        assertEquals(x[0][1000], y[0][0])
        assertEquals(x[1][20999], y[1][19999])
    }

    @Test
    fun fadesAreSmoothAndBounded() {
        val dir = TestSignals.tempDir()
        val pcm = TestSignals.toPcm(dir, "b.f32", sr, arrayOf(FloatArray(sr) { 0.5f }))
        val fin = TestSignals.readAll(AudioEdits.fade(pcm, 0, 4800, true, File(dir, "fi.f32")))[0]
        assertEquals(0f, fin[0])
        assertEquals(0.5f, fin[4800])
        for (i in 1 until 4800) assertTrue(fin[i] >= fin[i - 1])
        val fout = TestSignals.readAll(AudioEdits.fade(pcm, sr - 4800L, sr.toLong(), false, File(dir, "fo.f32")))[0]
        assertEquals(0.5f, fout[sr - 4801])
        assertTrue(fout[sr - 1] < 0.001f)
        assertEquals(sr.toLong(), pcm.frames)
    }

    @Test
    fun peaksCoverWholeFile() {
        val dir = TestSignals.tempDir()
        val x = TestSignals.sine(sr, 100.0, 0.7, 1.0)
        val p = Waveform.peaks(TestSignals.toPcm(dir, "c.f32", sr, x))
        assertEquals(((sr + 255) / 256) * 2, p.size)
        assertTrue(p.withIndex().filter { it.index % 2 == 1 }.maxOf { it.value } > 0.69f)
        assertTrue(p.withIndex().filter { it.index % 2 == 0 }.minOf { it.value } < -0.69f)
    }
}
