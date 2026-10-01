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
     * Делит [input] (48 кГц) на стемы модели (speech, music, sfx→effects). Каждый стем — файл той же длины.
     *
     * Модель принимает не звук, а спектр (как в рецепте экспорта): STFT моно-канала, n_fft 2048, окно Hann,
     * шаг 512, center + reflect, normalized; вход [B, 2(re/im), 1025, 751] — ровно 8 с; каналы идут батчем.
     * Выход — комплексные маски [B, 3, 2, 1025, 751]; стем = маска × спектр, затем обратное STFT.
     * [highQuality]: фрагменты перекрываются на 50 % вместо 25 % (каждая точка считается дважды).
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
            if (inShape.size != 4 || inShape[1] != 2L || inShape[2] != BINS.toLong()) {
                throw IOException("Неожиданный формат входа модели: ${inShape.contentToString()}")
            }
            val frames = inShape[3].toInt().let { if (it > 0) it else 751 }
            val chunk = (frames - 1) * HOP // 751 кадр ⇔ 384000 сэмплов = 8 с
            val names = (stemNamesFromManifest() ?: listOf("speech", "music", "sfx")).mapIndexed { i, n -> canonical(n, i) }
            val nStems = names.size

            val ch = input.channels
            val total = input.frames
            val overlap = if (highQuality) chunk / 2 else chunk / 4
            val hop = chunk - overlap
            val fadeIn = FloatArray(overlap) { i -> sin(PI / 2 * (i + 0.5) / overlap).let { (it * it).toFloat() } }
            val fadeOut = FloatArray(overlap) { i -> cos(PI / 2 * (i + 0.5) / overlap).let { (it * it).toFloat() } }
            val readBuf = Array(ch) { FloatArray(chunk) }
            val rows = Array(nStems) { Array(ch) { FloatArray(chunk) } }
            val pending = Array(nStems) { Array(ch) { FloatArray(overlap) } }
            val nChunks = if (total <= chunk) 1L else 1L + (total - chunk + hop - 1) / hop
            val stft = Stft(chunk, frames)
            val specRe = Array(ch) { FloatArray(BINS * frames) }
            val specIm = Array(ch) { FloatArray(BINS * frames) }
            val inArr = FloatArray(ch * 2 * BINS * frames)
            val shape = longArrayOf(ch.toLong(), 2, BINS.toLong(), frames.toLong())
            for (nm in names) {
                val f = File(workDir, "stem_${nm}.f32")
                files += f
                writers += PcmWriter(f, SAMPLE_RATE, ch)
            }

            PcmReader(input).use { reader ->
                var start = 0L
                var k = 0L
                while (true) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    reader.seek(start)
                    val got = reader.read(readBuf, chunk)
                    for (c in 0 until ch) java.util.Arrays.fill(readBuf[c], got.coerceAtLeast(0), chunk, 0f)

                    // спектр каждого канала → батч модели
                    for (c in 0 until ch) {
                        stft.forward(readBuf[c], specRe[c], specIm[c])
                        val b = c * 2 * BINS * frames
                        System.arraycopy(specRe[c], 0, inArr, b, BINS * frames)
                        System.arraycopy(specIm[c], 0, inArr, b + BINS * frames, BINS * frames)
                    }
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(inArr), shape).use { tensor ->
                        session.run(mapOf(inName to tensor)).use { res ->
                            val t = res.get(0) as OnnxTensor
                            val m = t.floatBuffer
                            val plane = BINS * frames
                            val yr = FloatArray(plane)
                            val yi = FloatArray(plane)
                            for (s in 0 until nStems) for (c in 0 until ch) {
                                val base = ((c * nStems + s) * 2) * plane
                                val xr = specRe[c]
                                val xi = specIm[c]
                                for (i in 0 until plane) {
                                    val mr = m.get(base + i)
                                    val mi = m.get(base + plane + i)
                                    yr[i] = mr * xr[i] - mi * xi[i]
                                    yi[i] = mr * xi[i] + mi * xr[i]
                                }
                                stft.inverse(yr, yi, rows[s][c])
                            }
                        }
                    }

                    val last = start + chunk >= total
                    val emit = (if (last) total - start else hop.toLong()).coerceIn(0, chunk.toLong()).toInt()
                    for (s in 0 until nStems) {
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

private const val N_FFT = 2048
private const val HOP = 512
private const val BINS = N_FFT / 2 + 1

/**
 * STFT как torchaudio.Spectrogram(n_fft=2048, hop=512, периодический Hann, center=True, reflect, normalized=True)
 * и точное обратное преобразование (overlap-add с делением на сумму квадратов окна).
 */
internal class Stft(private val length: Int, private val frames: Int) {
    private val fft = org.jtransforms.fft.FloatFFT_1D(N_FFT.toLong())
    private val win = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    private val norm = kotlin.math.sqrt(win.sumOf { (it * it).toDouble() }).toFloat()
    private val pad = N_FFT / 2
    private val padded = FloatArray(length + 2 * pad)
    private val buf = FloatArray(N_FFT)
    private val acc = FloatArray(length + 2 * pad)
    private val env = FloatArray(length + 2 * pad).also { e ->
        for (t in 0 until frames) for (n in 0 until N_FFT) {
            val p = t * HOP + n
            if (p < e.size) e[p] += win[n] * win[n]
        }
    }

    /** Спектр в раскладке [bin][frame] (как вход модели). */
    fun forward(x: FloatArray, re: FloatArray, im: FloatArray) {
        System.arraycopy(x, 0, padded, pad, length)
        for (k in 1..pad) {
            padded[pad - k] = x[k]                       // отражение в начале
            padded[pad + length - 1 + k] = x[length - 1 - k] // и в конце
        }
        for (t in 0 until frames) {
            val s = t * HOP
            for (n in 0 until N_FFT) buf[n] = padded[s + n] * win[n]
            fft.realForward(buf)
            re[t] = buf[0] / norm; im[t] = 0f
            re[(BINS - 1) * frames + t] = buf[1] / norm; im[(BINS - 1) * frames + t] = 0f
            for (f in 1 until BINS - 1) {
                re[f * frames + t] = buf[2 * f] / norm
                im[f * frames + t] = buf[2 * f + 1] / norm
            }
        }
    }

    fun inverse(re: FloatArray, im: FloatArray, out: FloatArray) {
        java.util.Arrays.fill(acc, 0f)
        for (t in 0 until frames) {
            buf[0] = re[t]
            buf[1] = re[(BINS - 1) * frames + t]
            for (f in 1 until BINS - 1) {
                buf[2 * f] = re[f * frames + t]
                buf[2 * f + 1] = im[f * frames + t]
            }
            fft.realInverse(buf, true)
            val s = t * HOP
            for (n in 0 until N_FFT) acc[s + n] += buf[n] * win[n] * norm
        }
        for (j in 0 until length) {
            val e = env[j + pad]
            out[j] = if (e > 1e-8f) acc[j + pad] / e else 0f
        }
    }
}
