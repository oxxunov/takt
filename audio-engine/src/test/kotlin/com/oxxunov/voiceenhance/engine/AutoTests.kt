package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoTests {
    private val sr = 16000

    /** Слоги: гармонический тон с плавной огибающей, случайные паузы. */
    private fun syllables(seconds: Double, seed: Int): DoubleArray {
        val n = (sr * seconds).toInt()
        val rnd = Random(seed)
        val x = DoubleArray(n)
        var pos = 0
        while (pos < n) {
            val len = (sr * (0.06 + 0.12 * rnd.nextDouble())).toInt()
            val f0 = 110.0 + 80.0 * rnd.nextDouble()
            for (i in 0 until len) {
                if (pos + i >= n) break
                val env = 0.5 - 0.5 * cos(2 * PI * i / len)
                var v = 0.0
                for (h in 1..12) v += sin(2 * PI * f0 * h * (pos + i) / sr) / h
                x[pos + i] = v * env * 0.3
            }
            pos += len
            if (rnd.nextDouble() < 0.4) pos += (sr * (0.1 + 0.3 * rnd.nextDouble())).toInt()
        }
        return x
    }

    private fun reverb(x: DoubleArray, t60: Double, seed: Int): DoubleArray {
        val rnd = Random(seed)
        val len = (t60 * 1.5 * sr).toInt()
        val rir = DoubleArray(len) { i -> (rnd.nextDouble() * 2 - 1) * 1.7 * exp(-6.9 * i / sr / t60) * 0.05 }
        rir[0] = 1.0
        val y = DoubleArray(x.size)
        for (i in x.indices) {
            val v = x[i]
            if (v == 0.0) continue
            val lim = minOf(len, x.size - i)
            for (j in 0 until lim) y[i + j] += v * rir[j]
        }
        return y
    }

    private fun toPcm(dir: File, name: String, x: DoubleArray, noise: Double = 1e-4, seed: Int = 5): PcmFile {
        val rnd = Random(seed)
        var peak = 0.0
        for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
        val arr = FloatArray(x.size) { (x[it] / peak * 0.8 + (rnd.nextDouble() * 2 - 1) * noise).toFloat() }
        return TestSignals.toPcm(dir, name, sr, arrayOf(arr))
    }

    @Test
    fun reverbIsDetectedAndOnlyThenDereverbed() {
        val dir = TestSignals.tempDir()
        val dry = syllables(12.0, 1)
        val dryPcm = toPcm(dir, "dry.f32", dry)
        val wetPcm = toPcm(dir, "wet.f32", reverb(dry, 0.9, 2))
        val pDry = VoiceProfiler.profile(dryPcm)
        val pWet = VoiceProfiler.profile(wetPcm)
        println("T60 оценка: сухо=${pDry.t60Seconds}, реверб=${pWet.t60Seconds}")
        assertTrue((pWet.t60Seconds ?: 0.0) > (pDry.t60Seconds ?: 0.0) + 0.15)
        val aDry = AudioAnalyzer.analyze(dryPcm)
        val aWet = AudioAnalyzer.analyze(wetPcm)
        assertFalse(AutoEnhancer.suggest(aDry, pDry, 0.6).settings.deReverb.enabled)
        assertTrue(AutoEnhancer.suggest(aWet, pWet, 0.6).settings.deReverb.enabled)
    }

    @Test
    fun noiseTriggersDenoiserOnlyWhenNeeded() {
        val dir = TestSignals.tempDir()
        val dry = syllables(8.0, 3)
        val clean = toPcm(dir, "clean.f32", dry, noise = 1e-5)
        val noisy = toPcm(dir, "noisy.f32", dry, noise = 0.03)
        val rClean = AutoEnhancer.suggest(AudioAnalyzer.analyze(clean), VoiceProfiler.profile(clean), 0.5)
        val rNoisy = AutoEnhancer.suggest(AudioAnalyzer.analyze(noisy), VoiceProfiler.profile(noisy), 0.5)
        assertFalse(rClean.settings.noiseReduction.enabled, rClean.notes.joinToString())
        assertTrue(rNoisy.settings.noiseReduction.enabled, rNoisy.notes.joinToString())
    }

    @Test
    fun strengthZeroIsNeutralAndAllStrengthsAreSane() {
        val dir = TestSignals.tempDir()
        val pcm = toPcm(dir, "s.f32", reverb(syllables(8.0, 4), 0.6, 6), noise = 0.01)
        val a = AudioAnalyzer.analyze(pcm)
        val p = VoiceProfiler.profile(pcm)
        val zero = AutoEnhancer.suggest(a, p, 0.0).settings
        assertFalse(zero.noiseReduction.enabled)
        assertFalse(zero.deReverb.enabled)
        assertFalse(zero.compressor.enabled)
        assertFalse(zero.deEsser.enabled)
        assertTrue(zero.eq.bands.all { it.gainDb == 0.0 })
        var prevAtten = 0.0
        for (i in 0..10) {
            val st = AutoEnhancer.suggest(a, p, i / 10.0).settings
            assertTrue(st.eq.bands.all { it.gainDb in -5.0..4.0 && !it.gainDb.isNaN() })
            assertTrue(st.deReverb.strength in 0.0..100.0)
            assertTrue(st.compressor.ratio in 1.0..4.5)
            if (st.noiseReduction.enabled) {
                assertTrue(st.noiseReduction.attenLimitDb >= prevAtten, "подавление должно расти с силой")
                prevAtten = st.noiseReduction.attenLimitDb
            }
        }
    }

    @Test
    fun presetsAreValid() {
        assertTrue(VoicePresets.all.size == 8)
        val dir = TestSignals.tempDir()
        val pcm = toPcm(dir, "pr.f32", syllables(2.0, 9))
        for (pr in VoicePresets.all) {
            // DSP-часть пресета должна отрабатывать без ошибок (ML-блоки тут не подключены)
            val st = pr.settings.copy(noiseReduction = NoiseReductionSettings(false), deReverb = DeReverbSettings(false))
            val out = EnhancementPipeline.process(pcm, File(dir, "pr_out.f32"), dir, st, AudioAnalyzer.analyze(pcm))
            assertTrue(out.frames == pcm.frames)
            TestSignals.assertFinite(TestSignals.readAll(out))
        }
    }
}
