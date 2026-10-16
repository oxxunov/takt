package com.oxxunov.voiceenhance.ml

import android.content.Context
import com.oxxunov.voiceenhance.engine.FileStage
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * «Студийный голос» — Sidon v0.1 (SaruLab, MIT), полная версия FP16.
 * Генеративное восстановление речи: убирает шум и эхо комнаты и достраивает полный тембр до 48 кГц.
 * Многоязычная модель (обучена на десятках языков), всё офлайн.
 *
 * Путь звука: моно → 16 кГц (libsamplerate) → Sidon кусками ~20 с с реальным контекстом по краям,
 * разрезы в самых тихих местах → 48 кГц → частота исходника. Длительность сохраняется точно.
 * Стерео сводится в моно (для голоса это так и есть), результат кладётся во все каналы.
 */
class SidonRestorer(private val context: Context) {

    companion object {
        const val MODEL_ASSET = "sidon/sidon-v0.1-f16.gguf"
        private const val MODEL_FILE = "sidon-v0.1-f16.gguf"
        private const val SR_IN = 16_000
        private const val SR_OUT = 48_000

        /** Длина куска: автор модели отмечает, что на длинных входах качество падает. */
        private const val CHUNK = 20 * SR_IN
        /** Где искать тихое место для разреза — последняя секунда куска. */
        private const val SEARCH = SR_IN
        /** Реальный звук соседей по краям куска, потом обрезается (как в CrispASR). */
        private const val CONTEXT = SR_IN / 2
        private const val RMS_WIN = 1600
        private const val RMS_HOP = 160
        /** Потолок памяти на проход внимания, МБ. Куску 21 с хватает ~500 МБ. */
        private const val MEM_BUDGET_MB = 640
    }

    val isAvailable: Boolean get() = SidonNative.loadError == null && hasModelAsset()

    private fun hasModelAsset(): Boolean = try {
        context.assets.openFd(MODEL_ASSET).use { it.length > 0 }
    } catch (_: Exception) {
        false
    }

    /** Модель в assets без сжатия; ggml читает её с диска, поэтому один раз копируем в filesDir. */
    fun modelPath(progress: (Float) -> Unit = {}): String {
        val dst = File(context.filesDir, MODEL_FILE)
        val assetSize = try {
            context.assets.openFd(MODEL_ASSET).use { it.length }
        } catch (e: Exception) {
            throw IOException("Модель Sidon не найдена в приложении", e)
        }
        if (!dst.exists() || dst.length() != assetSize) {
            val tmp = File(context.filesDir, "$MODEL_FILE.tmp")
            context.assets.open(MODEL_ASSET).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress((done.toDouble() / assetSize).toFloat())
                    }
                }
            }
            dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("Не удалось сохранить модель Sidon")
        }
        return dst.absolutePath
    }

    fun stage(): FileStage = FileStage { input, output, progress, isCancelled ->
        run(input, output, progress, isCancelled)
    }

    fun run(
        input: PcmFile,
        output: File,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): PcmFile {
        SidonNative.loadError?.let {
            throw IOException("Студийный голос недоступен на этом устройстве (нужен 64-битный процессор): ${it.message}")
        }
        val dir = output.parentFile ?: throw IOException("Нет рабочей папки")
        val tag = System.nanoTime()
        val mono = File(dir, "sidon_mono_$tag.f32")
        val in16 = File(dir, "sidon_16k_$tag.f32")
        val out48 = File(dir, "sidon_48k_$tag.f32")
        val back = File(dir, "sidon_back_$tag.f32")
        var handle = 0L
        try {
            // 0–3 %: модель на диск (только в первый раз), 3–6 %: моно и 16 кГц
            val path = modelPath { progress(0.03f * it) }
            if (isCancelled()) throw ProcessingCancelledException()
            val monoPcm = toMono(input, mono, isCancelled)
            val pcm16 = Resampler.file(monoPcm, SR_IN, in16, { progress(0.03f + 0.03f * it) }, isCancelled)
            mono.delete()

            handle = SidonNative.init(path, threadCount(), MEM_BUDGET_MB)
            if (handle == 0L) throw IOException("Не удалось загрузить модель Sidon (мало памяти?)")

            // 6–94 %: сама модель
            restoreAll(handle, pcm16, out48, { progress(0.06f + 0.88f * it) }, isCancelled)
            SidonNative.free(handle)
            handle = 0L
            in16.delete()

            // 94–100 %: обратно в частоту исходника, длина — кадр в кадр
            val restored = Resampler.file(
                PcmFile(out48, SR_OUT, 1), input.sampleRate, back,
                { progress(0.94f + 0.05f * it) }, isCancelled, targetFrames = input.frames,
            )
            out48.delete()
            spread(restored, output, input.channels, isCancelled)
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            if (handle != 0L) SidonNative.free(handle)
            mono.delete(); in16.delete(); out48.delete(); back.delete()
        }
        progress(1f)
        return PcmFile(output, input.sampleRate, input.channels)
    }

    /** Режет 16 кГц файл на куски в тихих местах и прогоняет каждый через модель. */
    private fun restoreAll(
        handle: Long,
        pcm16: PcmFile,
        out48: File,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val total = pcm16.frames
        if (total > Int.MAX_VALUE / 4) throw IOException("Запись слишком длинная")
        val n = total.toInt()
        RandomAccessFile(pcm16.file, "r").use { raf ->
            PcmWriter(out48, SR_OUT, 1).use { w ->
                var start = 0
                while (start < n) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    val end = nextCut(raf, start, n)
                    val lo = maxOf(0, start - CONTEXT)
                    val hi = minOf(n, end + CONTEXT)
                    val core = (end - start) * 3
                    val x = readRange(raf, lo, hi - lo)
                    // модели нужно хотя бы 400 сэмплов — крошечный хвост просто дополняем тишиной
                    val padded = if (x.size < 400) x.copyOf(400) else x
                    val y = SidonNative.restore(handle, padded, 0, padded.size)
                        ?: throw IOException("Ошибка модели Sidon на ${start / SR_IN} с")
                    val lead = (start - lo) * 3
                    val piece = FloatArray(core)
                    val avail = (y.size - lead).coerceAtLeast(0)
                    System.arraycopy(y, minOf(lead, y.size), piece, 0, minOf(core, avail))
                    w.writeInterleaved(piece, core)
                    start = end
                    progress(start.toFloat() / n)
                }
            }
        }
    }

    /** Граница следующего куска: если до конца больше CHUNK — самое тихое место в последней секунде. */
    private fun nextCut(raf: RandomAccessFile, start: Int, n: Int): Int {
        if (n - start <= CHUNK) return n
        val from = start + CHUNK - SEARCH
        val x = readRange(raf, from, SEARCH)
        var best = SEARCH
        var bestRms = Double.MAX_VALUE
        var i = 0
        while (i + RMS_WIN <= x.size) {
            var s = 0.0
            for (k in i until i + RMS_WIN) s += x[k] * x[k]
            val rms = sqrt(s / RMS_WIN)
            if (rms < bestRms) {
                bestRms = rms
                best = i + RMS_WIN / 2
            }
            i += RMS_HOP
        }
        return from + best
    }

    private fun readRange(raf: RandomAccessFile, from: Int, len: Int): FloatArray {
        val bytes = ByteArray(len * 4)
        raf.seek(from.toLong() * 4)
        raf.readFully(bytes)
        val out = FloatArray(len)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun toMono(input: PcmFile, out: File, isCancelled: () -> Boolean): PcmFile {
        val ch = input.channels
        val block = 8192
        val buf = Array(ch) { FloatArray(block) }
        val m = arrayOf(FloatArray(block))
        PcmReader(input).use { r ->
            PcmWriter(out, input.sampleRate, 1).use { w ->
                while (true) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    val k = r.read(buf, block)
                    if (k <= 0) break
                    for (i in 0 until k) {
                        var s = 0f
                        for (c in 0 until ch) s += buf[c][i]
                        m[0][i] = s / ch
                    }
                    w.write(m, 0, k)
                }
            }
        }
        return PcmFile(out, input.sampleRate, 1)
    }

    private fun spread(mono: PcmFile, out: File, channels: Int, isCancelled: () -> Boolean) {
        val block = 8192
        val one = arrayOf(FloatArray(block))
        val many = Array(channels) { one[0] }
        PcmReader(mono).use { r ->
            PcmWriter(out, mono.sampleRate, channels).use { w ->
                while (true) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    val k = r.read(one, block)
                    if (k <= 0) break
                    w.write(many, 0, k)
                }
            }
        }
    }

    /** Потоки — по числу быстрых ядер: медленные ядра big.LITTLE только тормозят общий счёт. */
    private fun threadCount(): Int {
        val all = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return try {
            val freqs = (0 until all).mapNotNull {
                File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").takeIf { f -> f.exists() }
                    ?.readText()?.trim()?.toLongOrNull()
            }
            if (freqs.size < all) return (all / 2).coerceIn(1, 4)
            val top = freqs.max()
            freqs.count { it >= top * 0.85 }.coerceIn(2, 8).coerceAtMost(all)
        } catch (_: Throwable) {
            (all / 2).coerceIn(1, 4)
        }
    }
}
