package com.tupicgames.takt.engine.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Декодирует локальный файл в PCM целиком, в память.
 *
 * Работает одинаково для аудио и для видео: MediaExtractor выбирает первую
 * дорожку с аудио-mime, видеодорожка игнорируется. Ничего никуда не
 * отправляется — весь разбор идёт на устройстве.
 *
 * Декодируем ЦЕЛИКОМ и заранее, а не потоком во время игры. Потоковый
 * декодер в реальном времени — это половина всех отказов на старте уровня
 * и источник дрожания часов. Память дешевле надёжности: четыре минуты
 * стереозвука — около 40 МБ.
 */
object PcmDecoder {

    /** Чередующиеся 16-битные отсчёты: L R L R ... */
    class Pcm(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int
    ) {
        val frameCount: Int get() = if (channels > 0) samples.size / channels else 0
        val durationSec: Float
            get() = if (sampleRate > 0) frameCount.toFloat() / sampleRate else 0f

        /** Моно float для анализатора. */
        fun toMonoFloat(): FloatArray {
            val n = frameCount
            val out = FloatArray(n)
            var s = 0
            for (i in 0 until n) {
                var acc = 0f
                for (c in 0 until channels) acc += samples[s++] / 32768f
                out[i] = acc / channels
            }
            return out
        }
    }

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @param maxSeconds ограничение сверху; 0 — без ограничения
     * @param onProgress доля выполнения 0..1, вызывается из текущего потока
     */
    fun decode(
        context: Context,
        uri: Uri,
        maxSeconds: Int = 900,
        onProgress: ((Float) -> Unit)? = null
    ): Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw DecodeException("не удалось открыть файл: ${e.message}", e)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i; format = f; break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw DecodeException("в файле нет аудиодорожки")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: run { extractor.release(); throw DecodeException("у дорожки нет mime-типа") }

        val sampleRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE, 44100)
        var channels = format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L) else 0L
        val totalSec = (durationUs / 1_000_000.0).toFloat()

        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw DecodeException("нет декодера для $mime: ${e.message}", e)
        }

        // Длительность известна заранее — резервируем сразу. Рост удвоением
        // на длинном треке даёт пик вдвое больше нужного.
        val expectedFrames = if (durationUs > 0)
            (durationUs / 1_000_000.0 * sampleRate).toInt() else sampleRate * 60
        val out = ShortArrayBuilder((expectedFrames + sampleRate) * channels)

        val limitFrames = if (maxSeconds > 0) maxSeconds.toLong() * sampleRate else Long.MAX_VALUE
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastReport = 0f

        try {
            while (!outputDone && out.size / channels < limitFrames) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)
                        val n = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val oi = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Фактический формат выхода может отличаться от формата
                        // дорожки — и по каналам, и по типу отсчётов. Читать
                        // float-байты как short значит получить шум.
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* ждём */ }
                    else -> if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)
                        if (buf != null && info.size > 0) append(buf, info, encoding, out)
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true

                        if (onProgress != null && totalSec > 0f) {
                            val done = (out.size / channels).toFloat() / sampleRate / totalSec
                            if (done - lastReport > 0.02f) { lastReport = done; onProgress(done.coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        if (out.size == 0) throw DecodeException("декодер не выдал ни одного отсчёта")
        return Pcm(out.toArray(), sampleRate, channels)
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private fun append(
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoding: Int,
        out: ShortArrayBuilder
    ) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.order(ByteOrder.nativeOrder())
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val f = buf.asFloatBuffer()
                while (f.hasRemaining()) {
                    val v = (f.get() * 32767f).coerceIn(-32768f, 32767f)
                    out.add(v.toInt().toShort())
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (buf.hasRemaining()) {
                    val v = ((buf.get().toInt() and 0xFF) - 128) * 256
                    out.add(v.toShort())
                }
            }
            else -> {
                val sh = buf.asShortBuffer()
                while (sh.hasRemaining()) out.add(sh.get())
            }
        }
    }

    /** Растущий буфер без боксинга — список объектов на миллионах отсчётов недопустим. */
    private class ShortArrayBuilder(initial: Int) {
        private var data = ShortArray(initial.coerceIn(1024, 400_000_000))
        var size = 0
            private set

        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size + data.size / 2 + 1024)
            data[size++] = v
        }

        fun toArray(): ShortArray = if (size == data.size) data else data.copyOf(size)
    }
}
