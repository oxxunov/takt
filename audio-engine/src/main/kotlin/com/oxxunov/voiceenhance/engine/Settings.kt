package com.oxxunov.voiceenhance.engine

data class NoiseReductionSettings(
    /** Новое подключается выключенным — чтобы можно было сравнить. */
    val enabled: Boolean = false,
    /** Максимум подавления, дБ. 100 = без ограничения (официальное значение DeepFilterNet). */
    val attenLimitDb: Double = 100.0,
)

data class DeReverbSettings(
    /** Новое подключается выключенным — чтобы можно было сравнить. */
    val enabled: Boolean = false,
    /** 0..100 % — доля вычитаемой поздней реверберации. */
    val strength: Double = 70.0,
)

data class HighPassSettings(
    val enabled: Boolean = true,
    val frequencyHz: Double = 80.0,
    /** true = 24 дБ/окт (Butterworth 4-го порядка), false = 12 дБ/окт. */
    val slope24: Boolean = true,
)

data class EqBand(
    val frequencyHz: Double,
    val gainDb: Double = 0.0,
    val q: Double = 1.0,
)

data class EqSettings(
    val enabled: Boolean = true,
    val presetName: String = EqPresets.all.first().name,
    val bands: List<EqBand> = EqPresets.bands(EqPresets.all.first()),
)

data class CompressorSettings(
    val enabled: Boolean = true,
    /** Auto: порог считается от измеренного уровня речи, остальное — проверенные для речи значения. */
    val auto: Boolean = true,
    val thresholdDb: Double = -24.0,
    val ratio: Double = 3.0,
    val attackMs: Double = 10.0,
    val releaseMs: Double = 120.0,
    val kneeDb: Double = 6.0,
    val makeupDb: Double = 0.0,
)

data class DeEsserSettings(
    val enabled: Boolean = true,
    /** 0..100 %. */
    val amount: Double = 40.0,
    val frequencyHz: Double = 5500.0,
)

data class LimiterSettings(
    val enabled: Boolean = true,
    val ceilingDbTp: Double = -1.0,
    /** Итоговый уровень после лимитера, дБ (≤ 0). */
    val outputLevelDb: Double = 0.0,
    val releaseMs: Double = 80.0,
    val lookaheadMs: Double = 5.0,
)

enum class LoudnessTarget(val lufs: Double, val label: String) {
    /** Auto = −16 LUFS: стандарт для речи/подкастов на мобильных (Apple, большинство платформ). */
    AUTO(-16.0, "Auto"),
    M16(-16.0, "−16"),
    M14(-14.0, "−14"),
    M12(-12.0, "−12"),
    M10(-10.0, "−10"),
}

data class LoudnessSettings(
    val enabled: Boolean = true,
    val target: LoudnessTarget = LoudnessTarget.AUTO,
)

data class EnhanceSettings(
    val noiseReduction: NoiseReductionSettings = NoiseReductionSettings(),
    val deReverb: DeReverbSettings = DeReverbSettings(),
    val highPass: HighPassSettings = HighPassSettings(),
    val eq: EqSettings = EqSettings(),
    val compressor: CompressorSettings = CompressorSettings(),
    val deEsser: DeEsserSettings = DeEsserSettings(),
    val limiter: LimiterSettings = LimiterSettings(),
    val loudness: LoudnessSettings = LoudnessSettings(),
)

object EqPresets {
    val frequencies = doubleArrayOf(60.0, 100.0, 150.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 12000.0, 16000.0)

    class Preset(val name: String, val highPassHz: Double, val gains: DoubleArray)

    //                                   60    100   150   250   500   1k    2k    4k    8k    12k   16k
    val all: List<Preset> = listOf(
        Preset("Natural Voice", 70.0, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        Preset("Clear Voice", 90.0, doubleArrayOf(0.0, 0.0, 0.0, -2.0, -1.0, 0.0, 1.5, 2.0, 1.0, 0.0, 0.0)),
        Preset("Deep Voice", 60.0, doubleArrayOf(0.0, 2.5, 2.0, 0.0, -1.5, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)),
        Preset("Female Voice", 100.0, doubleArrayOf(0.0, 0.0, -1.0, -1.5, 0.0, 0.0, 1.0, 1.5, 1.5, 1.0, 0.0)),
        Preset("Male Voice", 70.0, doubleArrayOf(0.0, 1.0, 1.0, -2.0, -1.5, 0.0, 1.0, 2.0, 1.0, 0.0, 0.0)),
        Preset("Podcast", 80.0, doubleArrayOf(0.0, 1.5, 0.0, -2.5, -1.0, 0.0, 1.5, 2.0, 1.5, 1.0, 0.0)),
        Preset("Narration", 70.0, doubleArrayOf(0.0, 1.5, 1.0, -1.5, -1.0, 0.0, 1.0, 1.5, 1.0, 0.0, 0.0)),
        Preset("Dubbing", 90.0, doubleArrayOf(0.0, 0.0, -1.0, -2.0, -1.5, 0.0, 2.0, 2.5, 1.0, 1.0, 0.0)),
        Preset("Phone Recording", 120.0, doubleArrayOf(0.0, 0.0, -2.0, -3.0, -2.0, -1.0, 1.0, 2.0, 3.0, 2.0, 0.0)),
        Preset("Studio Voice", 80.0, doubleArrayOf(0.0, 1.0, 0.0, -1.5, -0.5, 0.0, 0.0, 1.0, 1.5, 2.0, 1.0)),
    )

    fun bands(p: Preset): List<EqBand> = frequencies.mapIndexed { i, f -> EqBand(f, p.gains[i], 1.0) }
}
