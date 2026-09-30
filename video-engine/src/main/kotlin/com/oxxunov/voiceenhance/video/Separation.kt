package com.oxxunov.voiceenhance.video

import android.content.Context
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.ml.BanditModel
import com.oxxunov.voiceenhance.ml.DeepFilterDenoiser
import com.oxxunov.voiceenhance.ml.Resampler
import java.io.File

/** Источник звука после разделения: speech / music / effects (или другой). */
class NamedStem(val name: String, val pcm: PcmFile)

/** Результат разделения. Всегда содержит стем «speech». */
class SeparationResult(val stems: List<NamedStem>) {
    val speech: PcmFile get() = stems.first { it.name == "speech" }.pcm
}

/**
 * Разделение звука на источники. Реализации взаимозаменяемы:
 * локальные модели сейчас, удалённая — если когда-нибудь появится свой сервер.
 */
interface AudioSeparator {
    val id: String
    fun separate(input: PcmFile, workDir: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): SeparationResult
}

/**
 * Быстрый режим: DeepFilterNet3 выделяет речь (модель уже есть в приложении),
 * фон = исходник − речь. Выходы модели выровнены по времени с входом, поэтому вычитание точное.
 */
class DeepFilterSpeechSeparator(private val context: Context) : AudioSeparator {
    override val id = "deepfilter"

    override fun separate(input: PcmFile, workDir: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): SeparationResult {
        val speech = DeepFilterDenoiser(context).run(
            input, File(workDir, "speech.f32"), 100.0, { progress(it) }, isCancelled,
        )
        return SeparationResult(listOf(NamedStem("speech", speech)))
    }
}

/**
 * Стандартное / высокое качество: BandIt v2 (кино-разделение речь / музыка / эффекты, многоязычная модель).
 * Песни и пение остаются — модель отличает диалог от вокала в музыке.
 * Модель работает на 48 кГц; стемы возвращаются на исходную частоту с точной длиной.
 */
class BanditSpeechSeparator(
    private val context: Context,
    /** «Высокое качество»: перекрытие фрагментов 50 % и усреднение по перестановке каналов. */
    private val highQuality: Boolean,
) : AudioSeparator {
    override val id = if (highQuality) "bandit_v2_hq" else "bandit_v2"

    override fun separate(input: PcmFile, workDir: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): SeparationResult {
        val model = BanditModel(context)
        if (!model.isAvailable()) throw java.io.IOException("Модель BandIt v2 не найдена в сборке")
        val sr = input.sampleRate
        val resample = sr != BanditModel.SAMPLE_RATE
        val in48 = if (resample) {
            Resampler.file(input, BanditModel.SAMPLE_RATE, File(workDir, "in48.f32"), { progress(it * 0.05f) }, isCancelled)
        } else input
        val dir48 = File(workDir, "stems48").apply { mkdirs() }
        val stems48 = model.extractStems(in48, dir48, { progress(0.05f + it * 0.85f) }, isCancelled, highQuality)
        if (resample) in48.file.delete()
        val out = stems48.mapIndexed { i, st ->
            val pcm = if (resample) {
                Resampler.file(
                    st.pcm, sr, File(workDir, "${st.name}.f32"),
                    { progress(0.9f + (i + it) / stems48.size * 0.1f) }, isCancelled, targetFrames = input.frames,
                ).also { st.pcm.file.delete() }
            } else st.pcm
            NamedStem(st.name, pcm)
        }
        progress(1f)
        return SeparationResult(out)
    }
}
