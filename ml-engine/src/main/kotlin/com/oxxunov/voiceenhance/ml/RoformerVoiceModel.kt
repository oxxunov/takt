package com.oxxunov.voiceenhance.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * BS-Roformer (viperx, вокал) — одна из сильнейших открытых моделей выделения голоса.
 * Выделяет ВЕСЬ голос: речь, крик, шёпот и пение в песнях. Работает на 44.1 кГц, стерео.
 *
 * STFT и обратное STFT встроены в ONNX при экспорте, поэтому модель получает обычный звук:
 * вход [1, 2, 352800] (ровно 8 с) → выход голоса [1, 2, 352800].
 * Длинный файл режется на фрагменты по 8 с с плавным перекрытием (sin²) — стыков не слышно.
 */
class RoformerVoiceModel(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val ASSET_DIR = "roformer"
        const val MODEL = "bs_roformer_vocals_44k.onnx"
        private const val CHUNK = 352800
    }

    fun isAvailable(): Boolean = try {
        context.assets.list(ASSET_DIR)?.contains(MODEL) == true
    } catch (_: Exception) {
        false
    }

    private fun modelPath(): String {
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val dst = File(dir, MODEL)
        val size = try { context.assets.openFd("$ASSET_DIR/$MODEL").use { it.length } } catch (_: Exception) { -1L }
        if (!dst.exists() || (size > 0 && dst.length() != size)) {
            val tmp = File(dir, "$MODEL.tmp")
            context.assets.open("$ASSET_DIR/$MODEL").use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
            dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("Не удалось подготовить модель")
        }
        return dst.absolutePath
    }

    /**
     * Выделяет голос из [input] (44.1 кГц). Возвращает файл той же длины и с тем же числом каналов.
     * Стерео идёт в модель как есть; моно и многоканальный звук — каждый канал как двойное моно.
     */
    fun extractVoice(
        input: PcmFile,
        out: File,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): PcmFile {
        require(input.sampleRate == SAMPLE_RATE) { "BS-Roformer работает на 44.1 кГц" }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = try {
            env.createSession(modelPath(), opts)
        } catch (e: OutOfMemoryError) {
            throw IOException("Недостаточно памяти для загрузки модели BS-Roformer")
        }
        try {
            val inName = session.inputNames.first()
            val shape = (session.inputInfo[inName]!!.info as TensorInfo).shape
            if (shape.size != 3 || shape[1] != 2L || shape[2] != CHUNK.toLong()) {
                throw IOException("Неожиданный формат входа модели: ${shape.contentToString()}")
            }
            val ch = input.channels
            val total = input.frames
            // перекрытие 25 %: каждый фрагмент 8 с, шаг 6 с
            val overlap = CHUNK / 4
            val hop = CHUNK - overlap
            val fadeIn = FloatArray(overlap) { i -> sin(PI / 2 * (i + 0.5) / overlap).let { (it * it).toFloat() } }
            val fadeOut = FloatArray(overlap) { i -> cos(PI / 2 * (i + 0.5) / overlap).let { (it * it).toFloat() } }
            // пары каналов, которые идут в модель как «стерео»
            val pairs: List<Pair<Int, Int>> = if (ch == 2) listOf(0 to 1) else (0 until ch).map { it to it }
            val readBuf = Array(ch) { FloatArray(CHUNK) }
            val rows = Array(ch) { FloatArray(CHUNK) }
            val pending = Array(ch) { FloatArray(overlap) }
            val inArr = FloatArray(2 * CHUNK)
            val tShape = longArrayOf(1, 2, CHUNK.toLong())
            val nChunks = if (total <= CHUNK) 1L else 1L + (total - CHUNK + hop - 1) / hop

            PcmWriter(out, SAMPLE_RATE, ch).use { writer ->
                PcmReader(input).use { reader ->
                    var start = 0L
                    var k = 0L
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        reader.seek(start)
                        val got = reader.read(readBuf, CHUNK)
                        for (c in 0 until ch) java.util.Arrays.fill(readBuf[c], got.coerceAtLeast(0), CHUNK, 0f)

                        for ((a, b) in pairs) {
                            System.arraycopy(readBuf[a], 0, inArr, 0, CHUNK)
                            System.arraycopy(readBuf[b], 0, inArr, CHUNK, CHUNK)
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(inArr), tShape).use { tensor ->
                                session.run(mapOf(inName to tensor)).use { res ->
                                    val y = (res.get(0) as OnnxTensor).floatBuffer
                                    if (a == b) {
                                        // двойное моно: среднее двух выходов
                                        val r = rows[a]
                                        for (i in 0 until CHUNK) r[i] = 0.5f * (y.get(i) + y.get(CHUNK + i))
                                    } else {
                                        y.position(0); y.get(rows[a], 0, CHUNK)
                                        y.get(rows[b], 0, CHUNK)
                                    }
                                }
                            }
                            if (isCancelled()) throw ProcessingCancelledException()
                        }

                        val last = start + CHUNK >= total
                        val emit = (if (last) total - start else hop.toLong()).coerceIn(0, CHUNK.toLong()).toInt()
                        for (c in 0 until ch) {
                            val row = rows[c]
                            if (k > 0) for (i in 0 until overlap) row[i] = pending[c][i] + row[i] * fadeIn[i]
                            if (!last) for (i in 0 until overlap) pending[c][i] = row[hop + i] * fadeOut[i]
                        }
                        writer.write(rows, 0, emit)
                        k++
                        progress((k.toDouble() / nChunks).toFloat().coerceAtMost(1f))
                        if (last) break
                        start += hop
                    }
                }
            }
            return PcmFile(out, SAMPLE_RATE, ch)
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            session.close()
            opts.close()
        }
    }
}
