package com.oxxunov.voiceenhance.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Удаление голоса: becruily Mel-Roformer «Deux» (CC BY-NC 4.0, только некоммерческое использование).
 * Модель (ONNX) получает стерео-смесь [1, 2, chunk] на своей частоте и отдаёт фон без голоса.
 * Открывается один раз на всю обработку проекта — [close] обязательно.
 */
class VoiceRemover(private val context: Context) : Closeable {

    companion object {
        private const val DIR = "deux"
        private const val MODEL = "deux_bg.onnx"
        private const val META = "deux.json"

        fun isAvailable(context: Context): Boolean = try {
            context.assets.list(DIR)?.contains(MODEL) == true
        } catch (_: Exception) {
            false
        }
    }

    val sampleRate: Int
    private val chunk: Int
    private val env = OrtEnvironment.getEnvironment()
    private val opts: OrtSession.SessionOptions
    private val session: OrtSession

    init {
        if (!isAvailable(context)) throw IOException("Модель удаления голоса не вошла в эту сборку")
        val meta = JSONObject(context.assets.open("$DIR/$META").bufferedReader().use { it.readText() })
        sampleRate = meta.getInt("sample_rate")
        chunk = meta.getInt("chunk")
        opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = try {
            env.createSession(modelPath(), opts)
        } catch (e: OutOfMemoryError) {
            opts.close()
            throw IOException("Недостаточно памяти для модели удаления голоса")
        }
    }

    private fun modelPath(): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val dst = File(dir, MODEL)
        val size = try { context.assets.openFd("$DIR/$MODEL").use { it.length } } catch (_: Exception) { -1L }
        if (!dst.exists() || (size > 0 && dst.length() != size)) {
            val tmp = File(dir, "$MODEL.tmp")
            context.assets.open("$DIR/$MODEL").use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
            dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("Не удалось подготовить модель удаления голоса")
        }
        return dst.absolutePath
    }

    /**
     * Фон без голоса для [input] (стерео, частота модели). Результат — файл той же длины.
     * Длинный отрезок режется на куски модели с перекрытием 25 % и плавным стыком (sin²).
     */
    fun background(input: PcmFile, out: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): PcmFile {
        require(input.sampleRate == sampleRate && input.channels == 2) { "Нужен стерео-звук ${sampleRate} Гц" }
        val total = input.frames
        val overlap = chunk / 4
        val hop = chunk - overlap
        val fadeIn = FloatArray(overlap) { i -> sin(PI / 2 * (i + 0.5) / overlap).let { (it * it).toFloat() } }
        val readBuf = Array(2) { FloatArray(chunk) }
        val rows = Array(2) { FloatArray(chunk) }
        val pending = Array(2) { FloatArray(overlap) }
        val inArr = FloatArray(2 * chunk)
        val shape = longArrayOf(1, 2, chunk.toLong())
        val inName = session.inputNames.first()
        val nChunks = if (total <= chunk) 1L else 1L + (total - chunk + hop - 1) / hop
        try {
            PcmWriter(out, sampleRate, 2).use { w ->
                PcmReader(input).use { r ->
                    var start = 0L
                    var k = 0L
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        r.seek(start)
                        val got = r.read(readBuf, chunk)
                        for (c in 0 until 2) java.util.Arrays.fill(readBuf[c], got.coerceAtLeast(0), chunk, 0f)
                        System.arraycopy(readBuf[0], 0, inArr, 0, chunk)
                        System.arraycopy(readBuf[1], 0, inArr, chunk, chunk)
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(inArr), shape).use { t ->
                            session.run(mapOf(inName to t)).use { res ->
                                val fb = (res.get(0) as OnnxTensor).floatBuffer
                                fb.position(0)
                                fb.get(rows[0], 0, chunk)
                                fb.get(rows[1], 0, chunk)
                            }
                        }
                        val last = start + chunk >= total
                        val emit = (if (last) total - start else hop.toLong()).coerceIn(0, chunk.toLong()).toInt()
                        for (c in 0 until 2) {
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
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return PcmFile(out, sampleRate, 2)
    }

    override fun close() {
        session.close()
        opts.close()
    }
}
