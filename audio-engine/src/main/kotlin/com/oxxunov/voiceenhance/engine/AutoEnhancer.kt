package com.oxxunov.voiceenhance.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** Что подобрал ✨ Enhance и почему. */
data class AutoResult(val settings: EnhanceSettings, val notes: List<String>)

/**
 * ✨ Enhance: подбирает настройки каждого блока по измерениям сигнала.
 * Сила (0..1) не умножает всё линейно — у каждого блока своя кривая, и блок включается,
 * только если анализ показал проблему, которую он решает.
 */
object AutoEnhancer {

    /** Типичный долговременный спектр речи по октавам относительно 1 кГц, дБ. */
    private val TARGET_REL = doubleArrayOf(0.0, 3.0, 4.0, 0.0, -5.0, -9.0, -14.0, -19.0)

    fun suggest(a: AudioAnalysis, p: VoiceProfile, strength: Double): AutoResult {
        val s = strength.coerceIn(0.0, 1.0)
        val notes = ArrayList<String>()

        // ---------- шум ----------
        val nSev = ((45.0 - a.snrDb) / 30.0).coerceIn(0.0, 1.0)
        val nrOn = s > 0.05 && (nSev > 0.1 || s >= 0.8)
        val atten = when {
            s >= 0.95 -> 100.0
            else -> (12.0 + 70.0 * curve(s, 1.3) * (0.4 + 0.6 * nSev)).roundToInt().toDouble()
        }
        val nr = NoiseReductionSettings(nrOn, atten)
        notes += when {
            !nrOn -> "Шум: SNR ≈ ${a.snrDb.roundToInt()} дБ — шумодав не нужен"
            atten >= 100.0 -> "Шум: SNR ≈ ${a.snrDb.roundToInt()} дБ — шумодав на максимум"
            else -> "Шум: SNR ≈ ${a.snrDb.roundToInt()} дБ — шумодав, подавление до ${atten.roundToInt()} дБ"
        }

        // ---------- реверберация ----------
        val t60 = p.t60Seconds
        val rSev = if (t60 == null) 0.0 else ((t60 - 0.25) / 0.45).coerceIn(0.0, 1.0)
        val drOn = rSev >= 0.15 && s >= 0.2
        var drStrength = 100.0 * min(1.0, rSev.pow(0.7) * (0.3 + 0.9 * s))
        if (s < 0.6) drStrength = min(drStrength, 60.0)
        val dr = DeReverbSettings(drOn, drStrength.roundToInt().toDouble().coerceIn(0.0, 100.0))
        notes += when {
            t60 == null -> "Эхо: мало данных для оценки — De-Reverb выключен"
            !drOn -> "Эхо: слабое (оценка ${"%.2f".format(t60)} с) — De-Reverb не нужен"
            else -> "Эхо: оценка ${"%.2f".format(t60)} с — De-Reverb ${dr.strength.roundToInt()}%"
        }

        // ---------- Low cut ----------
        val boom = excess(p, 0) // 150 Гц
        val lowNoisy = p.bandSnrDb[0] < 12.0
        val hpFreq = when {
            boom > 6.0 || lowNoisy -> if (s >= 0.6) 110.0 else 100.0
            else -> 80.0
        }
        val hp = HighPassSettings(s > 0.0, hpFreq, true)
        if (hpFreq > 80.0) notes += "Низ: гул/бубнение — срез ${hpFreq.roundToInt()} Гц"

        // ---------- EQ: коррекция к типичному спектру речи ----------
        val k = 0.25 + 0.35 * s
        val gains = DoubleArray(EqPresets.frequencies.size)
        val corrected = ArrayList<String>()
        for ((qi, f) in VoiceProfiler.BANDS.withIndex()) {
            if (qi == 0 || qi == 3) continue // 150 Гц — делает срез, 1 кГц — опорная
            val rel = p.speechBandsRel.getOrNull(qi) ?: continue
            if (rel.isNaN()) continue
            val diff = TARGET_REL[qi] - rel
            val dead = sign(diff) * max(0.0, abs(diff) - 2.0)
            var g = (dead * k).coerceIn(-5.0, 4.0)
            if (g > 0 && p.bandSnrDb[qi] < 15.0) g = 0.0 // не поднимаем полосу, где в основном шум
            g = (g * 2).roundToInt() / 2.0
            if (s == 0.0) g = 0.0
            val idx = EqPresets.frequencies.indexOfFirst { it == f }
            if (idx >= 0 && g != 0.0) {
                gains[idx] = g
                corrected += "${if (f >= 1000) "${(f / 1000).roundToInt()} кГц" else "${f.roundToInt()} Гц"} ${if (g > 0) "+" else ""}$g"
            }
        }
        val eq = EqSettings(true, "Auto", EqPresets.frequencies.mapIndexed { i, f -> EqBand(f, gains[i], 1.0) })
        notes += if (corrected.isEmpty()) "Тембр: близок к норме — EQ без изменений" else "Тембр: EQ ${corrected.joinToString(", ")}"

        // ---------- компрессор ----------
        val compOn = s >= 0.15 && a.speechLevelDb > -80.0
        val ratio = (1.5 + 2.5 * s + if (p.dynamicRangeDb > 15.0) 0.5 else 0.0).coerceAtMost(4.5)
        val comp = CompressorSettings(
            enabled = compOn,
            auto = false,
            thresholdDb = (a.speechLevelDb - (6.0 + 6.0 * s)).coerceIn(-60.0, -6.0),
            ratio = (ratio * 10).roundToInt() / 10.0,
            attackMs = 10.0,
            releaseMs = 150.0,
            kneeDb = 6.0,
            makeupDb = 0.0,
        )
        notes += if (compOn) "Громкость: разброс ${p.dynamicRangeDb.roundToInt()} дБ — компрессор ${comp.ratio}:1" else "Компрессор выключен"

        // ---------- де-эссер ----------
        val sibSev = ((p.sibilanceDb + 12.0) / 9.0).coerceIn(0.0, 1.0)
        val dsAmount = (100.0 * (sibSev * (0.3 + 0.6 * s)).coerceIn(0.0, 1.0)).roundToInt().toDouble()
        val ds = DeEsserSettings(s > 0.0 && dsAmount >= 10.0, dsAmount, 5500.0)
        notes += if (ds.enabled) "Свистящие: заметны — De-Esser ${dsAmount.roundToInt()}%" else "Свистящие: в норме"

        if (a.clippedSamples > 0) notes += "⚠ Клиппинг в записи: восстановить обрезанные пики нельзя, лучше перезаписать тише"

        val settings = EnhanceSettings(
            noiseReduction = nr,
            deReverb = dr,
            highPass = hp,
            eq = eq,
            compressor = comp,
            deEsser = ds,
            limiter = LimiterSettings(enabled = true, ceilingDbTp = -1.0),
            loudness = LoudnessSettings(enabled = true, target = LoudnessTarget.AUTO),
        )
        notes += "Громкость: нормализация −16 LUFS, лимитер −1 dBTP"
        return AutoResult(settings, notes)
    }

    private fun excess(p: VoiceProfile, qi: Int): Double {
        val rel = p.speechBandsRel.getOrNull(qi) ?: return 0.0
        return if (rel.isNaN()) 0.0 else rel - TARGET_REL[qi]
    }
}

/** Готовые пресеты из ТЗ. Сильные ML-блоки включаются там, где они и нужны по сценарию. */
object VoicePresets {
    class Preset(val name: String, val settings: EnhanceSettings)

    private fun eq(name: String): EqSettings {
        val p = EqPresets.all.first { it.name == name }
        return EqSettings(true, p.name, EqPresets.bands(p))
    }

    private fun hp(name: String, override: Double? = null) =
        HighPassSettings(true, override ?: EqPresets.all.first { it.name == name }.highPassHz, true)

    private fun make(
        nr: Double?, dr: Double?, eqName: String, deEss: Double, hpOverride: Double? = null,
    ) = EnhanceSettings(
        noiseReduction = NoiseReductionSettings(nr != null, nr ?: 100.0),
        deReverb = DeReverbSettings(dr != null, dr ?: 70.0),
        highPass = hp(eqName, hpOverride),
        eq = eq(eqName),
        compressor = CompressorSettings(enabled = true, auto = true),
        deEsser = DeEsserSettings(true, deEss, 5500.0),
        limiter = LimiterSettings(enabled = true, ceilingDbTp = -1.0),
        loudness = LoudnessSettings(true, LoudnessTarget.AUTO),
    )

    val all: List<Preset> = listOf(
        Preset("🎙 Clean Voice", make(30.0, null, "Clear Voice", 30.0)),
        Preset("🎬 Dubbing", make(100.0, 60.0, "Dubbing", 50.0)),
        Preset("🎤 Studio", make(40.0, 50.0, "Studio Voice", 40.0)),
        Preset("📻 Podcast", make(50.0, 40.0, "Podcast", 40.0)),
        Preset("📖 Narration", make(40.0, 40.0, "Narration", 35.0)),
        Preset("📱 Phone Recording", make(100.0, 60.0, "Phone Recording", 50.0)),
        Preset("🏠 Room Recording", make(60.0, 80.0, "Clear Voice", 40.0)),
        Preset("🚗 Outdoor Recording", make(100.0, null, "Clear Voice", 40.0, hpOverride = 120.0)),
    )
}
