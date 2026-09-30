package com.oxxunov.voiceenhance.engine

import com.oxxunov.voiceenhance.engine.TestSignals.assertFinite
import com.oxxunov.voiceenhance.engine.TestSignals.rmsDb
import com.oxxunov.voiceenhance.engine.TestSignals.runProc
import com.oxxunov.voiceenhance.engine.TestSignals.sine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterTests {
    private val sr = 48000

    @Test
    fun highPassCutsLowsKeepsMids() {
        val hp = HighPassSettings(true, 100.0, true)
        val low = runProc(HighPassProcessor(hp), sr, sine(sr, 30.0, 0.5, 2.0))
        val mid = runProc(HighPassProcessor(hp), sr, sine(sr, 1000.0, 0.5, 2.0))
        val ref = rmsDb(sine(sr, 1000.0, 0.5, 2.0)[0])
        assertTrue(rmsDb(low[0], sr, 2 * sr) - ref < -30.0, "30 Гц должны ослабиться > 30 дБ")
        assertEquals(ref, rmsDb(mid[0], sr, 2 * sr), 0.3)
    }

    @Test
    fun eqPeakGivesExpectedGain() {
        val bands = EqPresets.frequencies.map { EqBand(it, if (it == 1000.0) 6.0 else 0.0, 1.0) }
        val eq = EqSettings(true, "test", bands)
        val x = sine(sr, 1000.0, 0.1, 1.0)
        val y = runProc(ParametricEqProcessor(eq), sr, x)
        assertEquals(6.0, rmsDb(y[0], sr / 2, sr) - rmsDb(x[0], sr / 2, sr), 0.3)
        val x2 = sine(sr, 100.0, 0.1, 1.0)
        val y2 = runProc(ParametricEqProcessor(eq), sr, x2)
        assertEquals(0.0, rmsDb(y2[0], sr / 2, sr) - rmsDb(x2[0], sr / 2, sr), 0.3)
    }

    @Test
    fun allPresetsAreStable() {
        for (p in EqPresets.all) {
            val y = runProc(ParametricEqProcessor(EqSettings(true, p.name, EqPresets.bands(p))), 44100,
                TestSignals.speechLike(44100, 1.0, 1, 0.3))
            assertFinite(y)
        }
    }
}

class DynamicsTests {
    private val sr = 48000

    @Test
    fun compressorSteadyStateMatchesRatio() {
        val s = CompressorSettings(true, false, -20.0, 4.0, 5.0, 50.0, 0.0, 0.0)
        val y = runProc(CompressorProcessor(s, null), sr, sine(sr, 1000.0, 0.5, 2.0))
        // вход RMS −9.03 дБ → −20 + 10.97/4 = −17.26 дБ
        assertEquals(-17.26, rmsDb(y[0], sr * 3 / 2, 2 * sr), 0.6)
    }

    @Test
    fun compressorLeavesQuietSignal() {
        val s = CompressorSettings(true, false, -20.0, 4.0, 5.0, 50.0, 6.0, 0.0)
        val x = sine(sr, 1000.0, 0.03, 1.0)
        val y = runProc(CompressorProcessor(s, null), sr, x)
        assertEquals(rmsDb(x[0], sr / 2, sr), rmsDb(y[0], sr / 2, sr), 0.2)
    }

    @Test
    fun deEsserReducesSibilanceOnly() {
        val s = DeEsserSettings(true, 100.0, 5500.0)
        val hi = sine(sr, 7000.0, 0.3, 1.0)
        val yHi = runProc(DeEsserProcessor(s), sr, hi)
        assertTrue(rmsDb(yHi[0], sr / 2, sr) - rmsDb(hi[0], sr / 2, sr) < -5.0, "7 кГц должны ослабиться")
        val lo = sine(sr, 500.0, 0.3, 1.0)
        val yLo = runProc(DeEsserProcessor(s), sr, lo)
        assertEquals(0.0, rmsDb(yLo[0], sr / 2, sr) - rmsDb(lo[0], sr / 2, sr), 0.3)
    }

    @Test
    fun limiterHoldsTruePeakCeiling() {
        val x = TestSignals.speechLike(sr, 2.0, 2, 3.0) // сильно перегружен
        val y = runProc(LimiterProcessor(LimiterSettings(ceilingDbTp = -1.0)), sr, x)
        assertFinite(y)
        assertTrue(TestSignals.truePeakDb(y) <= -1.0 + 0.05, "true peak выше потолка: ${TestSignals.truePeakDb(y)}")
    }
}

class LoudnessTests {
    @Test
    fun sineMatchesBs1770() {
        for (sr in intArrayOf(44100, 48000)) {
            val x = sine(sr, 1000.0, 0.1, 5.0) // −20 dBFS пик → −23.0 LUFS (моно)
            val m = LoudnessMeter(sr, 1)
            m.process(x, 0, x[0].size)
            assertEquals(-23.0, m.integratedLufs(), 0.2)
        }
    }

    @Test
    fun silenceIsGated() {
        val m = LoudnessMeter(48000, 1)
        val x = arrayOf(FloatArray(48000 * 2))
        m.process(x, 0, x[0].size)
        assertTrue(m.integratedLufs().isInfinite())
    }
}

class PipelineTests {
    @Test
    fun normalizesToTargetAndKeepsLength() {
        val dir = TestSignals.tempDir()
        val sr = 48000
        val input = TestSignals.toPcm(dir, "in.f32", sr, sine(sr, 300.0, 0.05, 6.0))
        val settings = EnhanceSettings(
            compressor = CompressorSettings(enabled = false),
            deEsser = DeEsserSettings(enabled = false),
            loudness = LoudnessSettings(true, LoudnessTarget.M16),
        )
        val out = EnhancementPipeline.process(input, File(dir, "out.f32"), dir, settings, null)
        assertEquals(input.frames, out.frames)
        assertEquals(sr, out.sampleRate)
        assertEquals(1, out.channels)
        val a = AudioAnalyzer.analyze(out)
        assertEquals(-16.0, a.lufs, 0.5)
        assertTrue(a.truePeakDb <= -1.0 + 0.05)
    }

    @Test
    fun fullChainOnLoudStereoNoClipping() {
        val dir = TestSignals.tempDir()
        for (sr in intArrayOf(44100, 48000)) {
            val input = TestSignals.toPcm(dir, "in$sr.f32", sr, TestSignals.speechLike(sr, 4.0, 2, 1.2))
            val pre = AudioAnalyzer.analyze(input)
            val settings = EnhanceSettings(loudness = LoudnessSettings(true, LoudnessTarget.M10))
            val out = EnhancementPipeline.process(input, File(dir, "out$sr.f32"), dir, settings, pre)
            assertEquals(input.frames, out.frames)
            assertEquals(2, out.channels)
            val y = TestSignals.readAll(out)
            assertFinite(y)
            assertTrue(TestSignals.truePeakDb(y) <= -1.0 + 0.05)
            assertEquals(0L, AudioAnalyzer.analyze(out).clippedSamples)
        }
    }

    @Test
    fun preStageRunsFirstAndTempFilesAreRemoved() {
        val dir = TestSignals.tempDir()
        val input = TestSignals.toPcm(dir, "pre.f32", 48000, sine(48000, 440.0, 0.3, 1.0))
        var called = false
        val halve = FileStage { inp, out, _, _ ->
            called = true
            val x = TestSignals.readAll(inp)
            for (ch in x) for (i in ch.indices) ch[i] *= 0.5f
            TestSignals.toPcm(out.parentFile, out.name, inp.sampleRate, x)
        }
        val settings = EnhanceSettings(loudness = LoudnessSettings(enabled = false), compressor = CompressorSettings(enabled = false))
        val out = EnhancementPipeline.process(input, File(dir, "pre_out.f32"), dir, settings, null, preStages = listOf(halve))
        assertTrue(called)
        assertEquals(input.frames, out.frames)
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("pre0_") || it.name.startsWith("stage1_") })
    }

    @Test
    fun shortFileShorterThanLatency() {
        val dir = TestSignals.tempDir()
        val input = TestSignals.toPcm(dir, "short.f32", 48000, sine(48000, 440.0, 0.3, 0.003))
        val out = EnhancementPipeline.process(input, File(dir, "short_out.f32"), dir, EnhanceSettings(), null)
        assertEquals(input.frames, out.frames)
    }

    @Test
    fun cancellationStopsProcessing() {
        val dir = TestSignals.tempDir()
        val input = TestSignals.toPcm(dir, "c.f32", 48000, sine(48000, 440.0, 0.3, 3.0))
        var threw = false
        try {
            EnhancementPipeline.process(input, File(dir, "c_out.f32"), dir, EnhanceSettings(), null, isCancelled = { true })
        } catch (e: ProcessingCancelledException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(!File(dir, "c_out.f32").exists())
    }
}

class AnalyzerTests {
    @Test
    fun detectsClipping() {
        val dir = TestSignals.tempDir()
        val x = sine(48000, 200.0, 2.0, 1.0)
        for (i in x[0].indices) x[0][i] = x[0][i].coerceIn(-1f, 1f)
        val a = AudioAnalyzer.analyze(TestSignals.toPcm(dir, "clip.f32", 48000, x))
        assertTrue(a.clippedSamples > 1000)
        val b = AudioAnalyzer.analyze(TestSignals.toPcm(dir, "clean.f32", 48000, sine(48000, 200.0, 0.5, 1.0)))
        assertEquals(0L, b.clippedSamples)
        assertEquals(-6.02, b.peakDb, 0.05)
    }
}

class WavTests {
    private fun roundTrip(format: WavFormat, channels: Int, sr: Int, frames: Int): Pair<Array<FloatArray>, Array<FloatArray>> {
        val dir = TestSignals.tempDir()
        val x = Array(channels) { c -> FloatArray(frames) { i -> (0.7 * kotlin.math.sin(i * 0.01 * (c + 1))).toFloat() } }
        val pcm = TestSignals.toPcm(dir, "w.f32", sr, x)
        val bos = ByteArrayOutputStream()
        WavWriter.write(pcm, bos, format)
        val bytes = bos.toByteArray()
        val dataLen = frames.toLong() * channels * format.bits / 8
        assertEquals(44 + dataLen + (dataLen and 1L), bytes.size.toLong())
        val riffSize = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
        assertEquals(bytes.size - 8L, riffSize)
        val back = WavReader.read(ByteArrayInputStream(bytes), File(dir, "back.f32"))
        assertEquals(sr, back.sampleRate)
        assertEquals(channels, back.channels)
        assertEquals(frames.toLong(), back.frames)
        return x to TestSignals.readAll(back)
    }

    private fun maxErr(a: Array<FloatArray>, b: Array<FloatArray>): Double {
        var m = 0.0
        for (c in a.indices) for (i in a[c].indices) m = maxOf(m, abs(a[c][i] - b[c][i]).toDouble())
        return m
    }

    @Test
    fun pcm16RoundTrip() {
        val (x, y) = roundTrip(WavFormat.PCM16, 2, 44100, 10000)
        assertTrue(maxErr(x, y) < 1.0e-4)
    }

    @Test
    fun pcm24RoundTrip() {
        val (x, y) = roundTrip(WavFormat.PCM24, 1, 48000, 10001) // нечётный размер → байт выравнивания
        assertTrue(maxErr(x, y) < 5.0e-7)
    }

    @Test
    fun floatRoundTripIsExact() {
        val (x, y) = roundTrip(WavFormat.FLOAT32, 2, 48000, 5000)
        assertEquals(0.0, maxErr(x, y))
    }
}
