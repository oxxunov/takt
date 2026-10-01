package com.oxxunov.voiceenhance.ml

import android.content.Context
import com.oxxunov.voiceenhance.engine.FileStage
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import java.io.File
import java.io.IOException

/**
 * Шумоподавление DeepFilterNet3 (официальная модель и рантайм, MIT/Apache-2.0).
 * Работает офлайн, на любой частоте дискретизации: libsamplerate переводит в 48 кГц и обратно.
 * Каждый канал обрабатывается своим экземпляром модели. Длительность сохраняется точно.
 */
class DeepFilterDenoiser(private val context: Context) {

    companion object {
        /**
         * В APK модель лежит как .tgz: файлы *.gz сборщик Android распаковывает и переименовывает
         * (без .gz), из-за чего ассет «пропадал». На диск копируется под исходным именем.
         */
        const val MODEL_ASSET = "DeepFilterNet3_onnx.tgz"
        private const val MODEL_FILE = "DeepFilterNet3_onnx.tar.gz"
        private const val BLOCK = 8192
    }

    /** Модель лежит в assets; C API читает её с диска, поэтому копируем один раз в filesDir. */
    fun modelPath(): String {
        val dst = File(context.filesDir, MODEL_FILE)
        val assetSize = try {
            context.assets.openFd(MODEL_ASSET).use { it.length }
        } catch (_: Exception) {
            -1L
        }
        if (!dst.exists() || (assetSize > 0 && dst.length() != assetSize)) {
            val tmp = File(context.filesDir, "$MODEL_FILE.tmp")
            context.assets.open(MODEL_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dst)) throw IOException("Не удалось сохранить модель")
        }
        return dst.absolutePath
    }

    /** Возвращает FileStage для конвейера. [attenLimitDb] — максимум подавления (100 = без ограничения). */
    fun stage(attenLimitDb: Double): FileStage = FileStage { input, output, progress, isCancelled ->
        run(input, output, attenLimitDb, progress, isCancelled)
    }

    fun run(
        input: PcmFile,
        output: File,
        attenLimitDb: Double,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): PcmFile {
        DeepFilterNative.loadError?.let {
            throw IOException("Шумодав недоступен на этом устройстве (нужен 64-битный процессор): ${it.message}")
        }
        val path = modelPath()
        val ch = input.channels
        val handles = LongArray(ch)
        try {
            for (c in 0 until ch) {
                handles[c] = DeepFilterNative.create(path, attenLimitDb.toFloat(), input.sampleRate)
                if (handles[c] == 0L) throw IOException("Не удалось загрузить модель DeepFilterNet3")
            }
            val total = input.frames
            val queues = Array(ch) { FloatQueue() }
            val buf = Array(ch) { FloatArray(BLOCK) }
            val outBuf = Array(ch) { FloatArray(BLOCK) }
            var written = 0L

            PcmReader(input).use { reader ->
                PcmWriter(output, input.sampleRate, ch).use { writer ->
                    fun drain(final: Boolean) {
                        while (written < total) {
                            var avail = queues.minOf { it.size }
                            if (avail == 0) break
                            avail = minOf(avail.toLong(), total - written, BLOCK.toLong()).toInt()
                            for (c in 0 until ch) queues[c].take(outBuf[c], avail)
                            writer.write(outBuf, 0, avail)
                            written += avail
                        }
                        if (final && written < total) {
                            // хвост ресемплера может быть на 1–2 сэмпла короче — дополняем тишиной
                            for (c in 0 until ch) java.util.Arrays.fill(outBuf[c], 0f)
                            while (written < total) {
                                val n = minOf(BLOCK.toLong(), total - written).toInt()
                                writer.write(outBuf, 0, n)
                                written += n
                            }
                        }
                    }

                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        val n = reader.read(buf, BLOCK)
                        if (n <= 0) break
                        for (c in 0 until ch) {
                            val y = DeepFilterNative.process(handles[c], buf[c], n)
                                ?: throw IOException("Ошибка шумодава")
                            queues[c].put(y)
                        }
                        drain(false)
                        done += n
                        if (total > 0) progress((done.toDouble() / total).toFloat())
                    }
                    for (c in 0 until ch) {
                        val y = DeepFilterNative.flush(handles[c]) ?: throw IOException("Ошибка шумодава")
                        queues[c].put(y)
                    }
                    drain(true)
                }
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            for (h in handles) if (h != 0L) DeepFilterNative.destroy(h)
        }
        progress(1f)
        return PcmFile(output, input.sampleRate, ch)
    }

    private class FloatQueue {
        private var data = FloatArray(1 shl 15)
        private var head = 0
        private var tail = 0
        val size: Int get() = tail - head

        fun put(x: FloatArray) {
            if (x.isEmpty()) return
            if (tail + x.size > data.size) {
                val live = size
                if (live + x.size > data.size) {
                    var cap = data.size
                    while (cap < live + x.size) cap *= 2
                    val nd = FloatArray(cap)
                    System.arraycopy(data, head, nd, 0, live)
                    data = nd
                } else {
                    System.arraycopy(data, head, data, 0, live)
                }
                head = 0
                tail = live
            }
            System.arraycopy(x, 0, data, tail, x.size)
            tail += x.size
        }

        fun take(dst: FloatArray, n: Int) {
            System.arraycopy(data, head, dst, 0, n)
            head += n
            if (head == tail) {
                head = 0; tail = 0
            }
        }
    }
}
