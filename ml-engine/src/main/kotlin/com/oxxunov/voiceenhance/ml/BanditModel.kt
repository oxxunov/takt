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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * BandIt v2 (multilingual) — разделение кино-звука на речь, музыку и эффекты.
 * Готовая ONNX-конвертация официальных весов (CC BY-SA 4.0), запуск через ONNX Runtime на CPU.
 * Модель работает на 48 кГц; длинный файл режется на фрагменты с плавным перекрытием (sin²),
 * поэтому стыков не слышно. Формат входа/выхода модели определяется автоматически по её описанию.
 */
class BanditModel(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 48000
        const val ASSET_DIR = "bandit"
        const val MODEL = "bandit_v2_multi_48k.onnx"
        private const val DEFAULT_CHUNK = 6 * SAMPLE_RATE
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
            if (!tmp.renameTo(dst)) throw IOException("Не удалось подготовить модель")
        }
        return dst.absolutePath
    }

    /** Индекс стема «speech» из манифеста экспорта (если он там описан). */
    private fun speechIndexFromManifest(): Int? = try {
        val text = context.assets.open("$ASSET_DIR/export-manifest.json").bufferedReader().use { it.readText() }
        findSpeech(JSONObject(text))
    } catch (_: Exception) {
        null
    }

    private fun findSpeech(v: Any?): Int? = when (v) {
        is JSONObject -> v.keys().asSequence().firstNotNullOfOrNull { findSpeech(v.opt(it)) }
        is JSONArray -> {
            val strings = (0 until v.length()).map { v.opt(it) }
            val idx = strings.indexOfFirst { it is String && (it.equals("speech", true) || it.equals("dialogue", true)) }
            if (idx >= 0 && strings.all { it is String }) idx
            else strings.firstNotNullOfOrNull { findSpeech(it) }
        }
        else -> null
    }

    /** Стем на выходе модели: каноническое имя (speech / music / effects) и файл. */
    class Stem(val name: String, val pcm: PcmFile)

    private fun canonical(name: String, index: Int): String = when {
        Regex("speech|dialog|dx", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "speech"
        Regex("music|mus", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "music"
        Regex("effect|sfx|fx", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "effects"
        else -> listOf("speech", "music", "effects").getOrElse(index) { "stem$index" }
    }

    /** Имена стемов из манифеста экспорта (массив, где есть «speech»). */
    private fun stemNamesFromManifest(): List<String>? = try {
        val text = context.assets.open("$ASSET_DIR/export-manifest.json").bufferedReader().use { it.readText() }
        findNames(JSONObject(text))
    } catch (_: Exception) {
        null
    }

    private fun findNames(v: Any?): List<String>? = when (v) {
        is JSONObject -> v.keys().asSequence().firstNotNullOfOrNull { findNames(v.opt(it)) }
        is JSONArray -> {
            val items = (0 until v.length()).map { v.opt(it) }
            if (items.isNotEmpty() && items.all { it is String } && items.any { (it as String).equals("speech", true) })
                items.map { it as String }
            else items.firstNotNullOfOrNull { findNames(it) }
        }
        else -> null
    }

    /**
     * Делит [input] (48 кГц) на стемы модели (речь, музыка, эффекты). Каждый стем — файл той же длины.
     * [highQuality]: перекрытие фрагментов 50 % и, для стерео, усреднение с прогоном при переставленных каналах.
     */
    fun extractStems(
        input: PcmFile,
        workDir: File,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        highQuality: Boolean = false,
    ): List<Stem> {
        require(input.sampleRate == SAMPLE_RATE) { "BandIt работает на 48 кГц" }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = try {
            env.createSession(modelPath(), opts)
        } catch (e: OutOfMemoryError) {
            throw IOException("Недостаточно памяти для загрузки модели BandIt")
        }
        val writers = ArrayList<PcmWriter>()
        val files = ArrayList<File>()
        try {
            val inName = session.inputNames.first()
            val inShape = (session.inputInfo[inName]!!.info as TensorInfo).shape
            if (inShape.size !in 2..3) throw IOException("Неожиданный формат входа модели: ${inShape.contentToString()}")
            val modelCh = inShape[inShape.size - 2].toInt().let { if (it > 0) it else 2 }
            val chunk = inShape.last().toInt().let { if (it > 0) it else DEFAULT_CHUNK }
            val outNames = session.outputNames.toList()
            val manifestNames = stemNamesFromManifest()

            val ch = input.channels
            val total = input.frames
            val overlap = if (highQuality) chunk / 2 else chunk / 4
            val swapTta = highQuality && modelCh == 2 && ch == 2
            val hop = chunk - overlap
            val fadeIn = FloatArray(overlap) { i -> sin(PI / 2 * (i + 0.5) / overlap).let { (it * it).toFloat() } }
            val fadeOut = FloatArray(overlap) { i -> cos(PI / 2 * (i + 0.5) / overlap).let { (it * it).toFloat() } }
            val readBuf = Array(ch) { FloatArray(chunk) }
            val nChunks = if (total <= chunk) 1L else 1L + (total - chunk + hop - 1) / hop
            val shape = if (inShape.size == 3) longArrayOf(1, modelCh.toLong(), chunk.toLong()) else longArrayOf(modelCh.toLong(), chunk.toLong())
            val inArr = FloatArray(modelCh * chunk)

            var names: List<String> = emptyList()
            var rows: Array<Array<FloatArray>> = emptyArray()
            var pending: Array<Array<FloatArray>> = emptyArray()

            fun infer(swap: Boolean, acc: Boolean) {
                for (mc in 0 until modelCh) {
                    if (ch > modelCh && modelCh == 1) {
                        for (i in 0 until chunk) { var sum = 0f; for (c in 0 until ch) sum += readBuf[c][i]; inArr[i] = sum / ch }
                    } else {
                        val srcCh = if (swap) (ch - 1 - min(mc, ch - 1)) else min(mc, ch - 1)
                        System.arraycopy(readBuf[srcCh], 0, inArr, mc * chunk, chunk)
                    }
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(inArr), shape).use { tensor ->
                    session.run(mapOf(inName to tensor)).use { res ->
                        // (имя, буфер, смещение, каналы, длина) для каждого стема
                        class View(val name: String, val fb: FloatBuffer, val base: Long, val oC: Int, val oT: Int)
                        val views = ArrayList<View>()
                        if (outNames.size >= 2) {
                            for ((i, n) in outNames.withIndex()) {
                                val t = res.get(n).get() as OnnxTensor
                                val os = (t.info as TensorInfo).shape
                                views += View(canonical(n, i), t.floatBuffer, 0L, os[os.size - 2].toInt(), os.last().toInt())
                            }
                        } else {
                            val t = res.get(0) as OnnxTensor
                            val os = (t.info as TensorInfo).shape
                            val oC = os[os.size - 2].toInt()
                            val oT = os.last().toInt()
                            val nStems = when (os.size) {
                                4 -> os[1].toInt()
                                3 -> if (os[0] == 1L) 1 else os[0].toInt()
                                else -> 1
                            }
                            val fb = t.floatBuffer
                            for (k in 0 until nStems) {
                                val raw = manifestNames?.getOrNull(k) ?: ""
                                views += View(canonical(raw, k), fb, k.toLong() * oC * oT, oC, oT)
                            }
                        }
                        if (rows.isEmpty()) {
                            names = views.map { it.name }
                            rows = Array(views.size) { Array(ch) { FloatArray(chunk) } }
                            pending = Array(views.size) { Array(ch) { FloatArray(overlap) } }
                            for (nm in names) {
                                val f = File(workDir, "stem_${nm}.f32")
                                files += f
                                writers += PcmWriter(f, SAMPLE_RATE, ch)
                            }
                        }
                        for ((k, v) in views.withIndex()) {
                            val n = min(chunk, v.oT)
                            for (c in 0 until ch) {
                                val outCh = if (swap) ch - 1 - c else c
                                val mc = min(c, v.oC - 1)
                                val row = rows[k][outCh]
                                if (!acc) java.util.Arrays.fill(row, 0f)
                                for (i in 0 until n) {
                                    val x = v.fb.get((v.base + mc.toLong() * v.oT + i).toInt())
                                    row[i] = if (acc) (row[i] + x) * 0.5f else x
                                }
                            }
                        }
                    }
                }
            }

            PcmReader(input).use { reader ->
                var start = 0L
                var k = 0L
                while (true) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    reader.seek(start)
                    val got = reader.read(readBuf, chunk)
                    for (c in 0 until ch) java.util.Arrays.fill(readBuf[c], got.coerceAtLeast(0), chunk, 0f)
                    infer(swap = false, acc = false)
                    if (swapTta) infer(swap = true, acc = true)

                    val last = start + chunk >= total
                    val emit = (if (last) total - start else hop.toLong()).coerceIn(0, chunk.toLong()).toInt()
                    for (s in rows.indices) {
                        for (c in 0 until ch) {
                            val row = rows[s][c]
                            if (k > 0) for (i in 0 until overlap) row[i] = pending[s][c][i] + row[i] * fadeIn[i]
                            if (!last) for (i in 0 until overlap) pending[s][c][i] = row[hop + i] * fadeOut[i]
                        }
                        writers[s].write(rows[s], 0, emit)
                    }
                    k++
                    progress((k.toDouble() / nChunks).toFloat().coerceAtMost(1f))
                    if (last) break
                    start += hop
                }
            }
            writers.forEach { it.close() }
            if (names.none { it == "speech" }) throw IOException("Модель не вернула стем речи")
            return names.mapIndexed { i, n -> Stem(n, PcmFile(files[i], SAMPLE_RATE, ch)) }
        } catch (e: Throwable) {
            writers.forEach { runCatching { it.close() } }
            files.forEach { it.delete() }
            throw e
        } finally {
            session.close()
            opts.close()
        }
    }
}
