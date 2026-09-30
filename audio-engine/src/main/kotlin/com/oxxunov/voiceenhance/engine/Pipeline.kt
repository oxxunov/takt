package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.min

/**
 * Этап, обрабатывающий файл целиком (например, ML-шумодав из модуля :ml-engine).
 * Должен сохранять частоту, число каналов и длительность.
 */
fun interface FileStage {
    fun run(input: PcmFile, output: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): PcmFile
}

/**
 * Конвейер обработки. Каждый блок независим и включается отдельно.
 *
 * Проход 0 (если включены): файловые этапы, например шумодав DeepFilterNet3.
 * Проход 1: High-pass → EQ → Compressor → De-esser (одновременно меряется LUFS результата).
 * Проход 2: усиление до целевой громкости → true-peak лимитер → выходной уровень.
 * Нормализация стоит ПЕРЕД лимитером — так лимитер гарантирует потолок уже после подъёма громкости.
 */
object EnhancementPipeline {
    const val BLOCK = 4096

    fun process(
        input: PcmFile,
        output: File,
        workDir: File,
        settings: EnhanceSettings,
        inputAnalysis: AudioAnalysis?,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        preStages: List<FileStage> = emptyList(),
    ): PcmFile {
        val sr = input.sampleRate
        val ch = input.channels
        workDir.mkdirs()

        // файловые этапы (ML) самые долгие — им 60 % шкалы прогресса
        val preShare = if (preStages.isEmpty()) 0f else 0.6f
        var source = input
        val temps = ArrayList<File>()
        try {
            preStages.forEachIndexed { i, st ->
                val out = File(workDir, "pre${i}_${System.nanoTime()}.f32")
                temps += out
                val base = preShare * i / preStages.size
                val span = preShare / preStages.size
                val res = st.run(source, out, { progress(base + span * it) }, isCancelled)
                check(res.frames == source.frames && res.sampleRate == sr && res.channels == ch) {
                    "Этап изменил длительность или формат"
                }
                source = res
            }
            return processDsp(source, output, workDir, settings, inputAnalysis,
                { progress(preShare + (1f - preShare) * it) }, isCancelled)
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private fun processDsp(
        input: PcmFile,
        output: File,
        workDir: File,
        settings: EnhanceSettings,
        inputAnalysis: AudioAnalysis?,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): PcmFile {
        val sr = input.sampleRate
        val ch = input.channels

        val stage1 = buildList<AudioProcessor> {
            if (settings.highPass.enabled) add(HighPassProcessor(settings.highPass))
            if (settings.eq.enabled) add(ParametricEqProcessor(settings.eq))
            if (settings.compressor.enabled) add(CompressorProcessor(settings.compressor, inputAnalysis?.speechLevelDb))
            if (settings.deEsser.enabled) add(DeEsserProcessor(settings.deEsser))
        }
        stage1.forEach { it.prepare(sr, ch) }

        val mid = File(workDir, "stage1_${System.nanoTime()}.f32")
        try {
            val meter = LoudnessMeter(sr, ch)
            runChain(input, mid, stage1, meter, { progress(it * 0.5f) }, isCancelled)

            val gainDb = if (settings.loudness.enabled) {
                val measured = meter.integratedLufs()
                if (measured.isFinite()) (settings.loudness.target.lufs - measured).coerceIn(-30.0, 30.0) else 0.0
            } else 0.0

            val stage2 = buildList<AudioProcessor> {
                add(GainProcessor(gainDb))
                if (settings.limiter.enabled) add(LimiterProcessor(settings.limiter))
            }
            stage2.forEach { it.prepare(sr, ch) }
            runChain(PcmFile(mid, sr, ch), output, stage2, null, { progress(0.5f + it * 0.5f) }, isCancelled)
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            mid.delete()
        }
        progress(1f)
        return PcmFile(output, sr, ch)
    }

    /**
     * Прогоняет файл через цепочку блоками. Задержка блоков компенсируется:
     * первые latency кадров отбрасываются, в конце подаются нули — длительность сохраняется точно.
     */
    fun runChain(
        input: PcmFile,
        output: File,
        chain: List<AudioProcessor>,
        meter: LoudnessMeter?,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val ch = input.channels
        val total = input.frames
        val latency = chain.sumOf { it.latencyFrames }
        val buf = Array(ch) { FloatArray(BLOCK) }
        var skip = latency.toLong()

        PcmReader(input).use { reader ->
            PcmWriter(output, input.sampleRate, ch).use { writer ->
                fun emit(frames: Int) {
                    for (p in chain) p.process(buf, frames)
                    val off = min(skip, frames.toLong()).toInt()
                    skip -= off
                    val n = frames - off
                    if (n > 0) {
                        writer.write(buf, off, n)
                        meter?.process(buf, off, n)
                    }
                }

                var done = 0L
                while (true) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    val n = reader.read(buf, BLOCK)
                    if (n <= 0) break
                    emit(n)
                    done += n
                    if (total > 0) progress((done.toDouble() / total).toFloat())
                }
                var remaining = latency
                while (remaining > 0) {
                    val n = min(remaining, BLOCK)
                    for (c in 0 until ch) java.util.Arrays.fill(buf[c], 0, n, 0f)
                    emit(n)
                    remaining -= n
                }
            }
        }
    }
}
