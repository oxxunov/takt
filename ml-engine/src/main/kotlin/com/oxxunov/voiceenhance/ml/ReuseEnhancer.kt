package com.oxxunov.voiceenhance.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.oxxunov.voiceenhance.engine.FileStage
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * «Универсальная очистка» — NVIDIA NemotronLabs RE-USE (OpenMDW-1.1), ONNX Runtime на CPU.
 * Одна модель убирает шум, эхо, «плохой микрофон», последствия клиппинга.
 *
 * Путь звука (каждый канал отдельно): частота модели (libsamplerate) → нормализация громкости
 * как в inference.py → куски по 4 с с перекрытием 0,5 с (sin²) → обратно в частоту исходника.
 * Длительность сохраняется точно.
 */
class ReuseEnhancer(private val context: Context) {

    companion object {
        private const val DIR = "reuse"
        private const val MODEL = "reuse.onnx"
        private const val META = "reuse.json"
        private const val OVERLAP_S = 0.5
    }

    fun isAvailable(): Boolean = try {
        context.assets.list(DIR)?.contains(MODEL) == true
    } catch (_: Exception) {
        false
    }

    private fun modelPath(): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val dst = File(dir, MODEL)
        val size = try { context.assets.openFd("$DIR/$MODEL").use { it.length } } catch (_: Exception) { -1L }
        if (!dst.exists() || (size > 0 && dst.length() != size)) {
            val tmp = File(dir, "$MODEL.tmp")
            context.assets.open("$DIR/$MODEL").use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
            dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("Не удалось подготовить модель RE-USE")
        }
        return dst.absolutePath
    }

    private class Meta(val sampleRate: Int, val chunk: Int)

    private fun meta(): Meta {
        val o = JSONObject(context.assets.open("$DIR/$META").bufferedReader().use { it.readText() })
        return Meta(o.getInt("sample_rate"), o.getInt("chunk"))
    }

    fun stage(): FileStage = FileStage { input, output, progress, isCancelled ->
        run(input, output, progress, isCancelled)
    }

    fun run(input: PcmFile, output: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): PcmFile {
        if (!isAvailable()) throw IOException("Модель RE-USE не вошла в эту сборку")
        val m = meta()
        val dir = output.parentFile ?: throw IOException("Нет рабочей папки")
        val tag = System.nanoTime()
        val ch = input.channels
        val inModel = File(dir, "reuse_in_$tag.f32")
        val outModel = File(dir, "reuse_out_$tag.f32")
        val back = File(dir, "reuse_back_$tag.f32")
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = try {
            env.createSession(modelPath(), opts)
        } catch (e: OutOfMemoryError) {
            throw IOException("Недостаточно памяти для модели RE-USE")
        }
        try {
            // 0–5 %: в частоту модели (все каналы сразу)
            val pcmM = if (input.sampleRate == m.sampleRate) input
            else Resampler.file(input, m.sampleRate, inModel, { progress(0.05f * it) }, isCancelled)
            enhanceFile(env, session, pcmM, outModel, m, { progress(0.05f + 0.9f * it) }, isCancelled)
            if (pcmM !== input) inModel.delete()

            // 95–100 %: обратно, длина — кадр в кадр
            if (m.sampleRate == input.sampleRate) {
                outModel.renameTo(output) || run { outModel.copyTo(output, true); outModel.delete(); true }
            } else {
                Resampler.file(
                    PcmFile(outModel, m.sampleRate, ch), input.sampleRate, output,
                    { progress(0.95f + 0.05f * it) }, isCancelled, targetFrames = input.frames,
                )
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            session.close()
            opts.close()
            inModel.delete(); outModel.delete(); back.delete()
        }
        progress(1f)
        return PcmFile(output, input.sampleRate, ch)
    }

    /**
     * Потоково, без загрузки всего файла в память: проход 1 — громкость каждого канала,
     * проход 2 — куски по [Meta.chunk] с перекрытием; стык плавный (sin², сумма весов = 1).
     */
    private fun enhanceFile(
        env: OrtEnvironment,
        session: OrtSession,
        pcm: PcmFile,
        out: File,
        m: Meta,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val ch = pcm.channels
        val total = pcm.frames
        val chunk = m.chunk
        // проход 1: нормализация громкости, как в inference.py (RMS канала → 1)
        val energy = DoubleArray(ch)
        PcmReader(pcm).use { r ->
            val buf = Array(ch) { FloatArray(8192) }
            while (true) {
                if (isCancelled()) throw ProcessingCancelledException()
                val k = r.read(buf, 8192)
                if (k <= 0) break
                for (c in 0 until ch) for (i in 0 until k) energy[c] += buf[c][i].toDouble() * buf[c][i]
            }
        }
        val norm = FloatArray(ch) { c -> if (energy[c] > 1e-12) sqrt(total / energy[c]).toFloat() else 0f }

        val overlap = (OVERLAP_S * m.sampleRate).toInt().coerceAtMost(chunk / 4)
        val hop = chunk - overlap
        val fadeIn = FloatArray(overlap) { i -> sin(PI / 2 * (i + 0.5) / overlap).let { (it * it).toFloat() } }
        val readBuf = Array(ch) { FloatArray(chunk) }
        val rows = Array(ch) { FloatArray(chunk) }
        val pending = Array(ch) { FloatArray(overlap) }
        val inArr = FloatArray(chunk)
        val shape = longArrayOf(1, chunk.toLong())
        val inName = session.inputNames.first()
        val nChunks = if (total <= chunk) 1L else 1L + (total - chunk + hop - 1) / hop

        PcmWriter(out, m.sampleRate, ch).use { w ->
            PcmReader(pcm).use { r ->
                var start = 0L
                var k = 0L
                while (true) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    r.seek(start)
                    val got = r.read(readBuf, chunk)
                    for (c in 0 until ch) java.util.Arrays.fill(readBuf[c], got.coerceAtLeast(0), chunk, 0f)
                    for (c in 0 until ch) {
                        if (norm[c] == 0f) {
                            System.arraycopy(readBuf[c], 0, rows[c], 0, chunk) // тишина — как есть
                            continue
                        }
                        for (i in 0 until chunk) inArr[i] = readBuf[c][i] * norm[c]
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(inArr), shape).use { t ->
                            session.run(mapOf(inName to t)).use { res ->
                                val fb = (res.get(0) as OnnxTensor).floatBuffer
                                fb.get(rows[c], 0, chunk)
                            }
                        }
                        val inv = 1f / norm[c]
                        for (i in 0 until chunk) rows[c][i] *= inv
                    }
                    val last = start + chunk >= total
                    val emit = (if (last) total - start else hop.toLong()).coerceIn(0, chunk.toLong()).toInt()
                    for (c in 0 until ch) {
                        val row = rows[c]
                        if (k > 0) for (i in 0 until overlap) row[i] = pending[c][i] * (1f - fadeIn[i]) + row[i] * fadeIn[i]
                        if (!last) System.arraycopy(row, hop, pending[c], 0, overlap)
                    }
                    w.write(rows, 0, emit)
                    k++
                    progress((k.toDouble() / nChunks).toFloat().coerceAtMost(1f))
                    if (last) break
                    start += hop
                }
            }
        }
    }
}
