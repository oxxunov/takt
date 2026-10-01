package com.oxxunov.voiceenhance.audioio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import com.oxxunov.voiceenhance.engine.WavReader
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Импорт любого аудио во внутренний float-формат.
 * WAV читается своим парсером (точно, включая 24-bit и float), остальное — MediaCodec (MP3, AAC/M4A, FLAC, OGG, OPUS…).
 * Частота дискретизации сохраняется исходной.
 */
class AudioImporter(private val context: Context) {

    fun import(
        uri: Uri,
        outFile: File,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PcmFile {
        val resolver = context.contentResolver
        val head = ByteArray(12)
        val headLen = resolver.openInputStream(uri)?.use { ins ->
            var got = 0
            while (got < 12) {
                val r = ins.read(head, got, 12 - got)
                if (r < 0) break
                got += r
            }
            got
        } ?: throw IOException("Не удалось открыть файл")

        if (headLen == 12 && WavReader.isWav(head)) {
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            return resolver.openInputStream(uri)?.use { ins ->
                WavReader.read(ins, outFile, size, progress, isCancelled)
            } ?: throw IOException("Не удалось открыть файл")
        }
        return decodeWithMediaCodec(uri, outFile, progress, isCancelled, null).pcm
    }

    /** Результат декодирования конкретной дорожки: звук и время первого сэмпла (для синхронизации с видео). */
    class DecodedTrack(val pcm: PcmFile, val firstPtsUs: Long)

    /** Декодирует дорожку [trackIndex] контейнера (например, аудио из видео). */
    fun decodeTrack(
        uri: Uri,
        trackIndex: Int,
        outFile: File,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): DecodedTrack = decodeWithMediaCodec(uri, outFile, progress, isCancelled, trackIndex)

    private fun decodeWithMediaCodec(
        uri: Uri,
        outFile: File,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        trackIndex: Int?,
    ): DecodedTrack {
        val extractor = try {
            Demuxers.open(context, uri)
        } catch (e: Exception) {
            throw IOException("Файл повреждён или формат не поддерживается")
        }
        var codec: MediaCodec? = null
        var writer: PcmWriter? = null
        try {
            val track = trackIndex ?: (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            var inChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var conv = FloatArray(0)
            var firstPts = Long.MIN_VALUE

            fun readOutputFormat(of: MediaFormat) {
                inChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                else AudioFormat.ENCODING_PCM_16BIT
            }

            while (!outputDone) {
                if (isCancelled()) throw ProcessingCancelledException()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    readOutputFormat(c.outputFormat)
                } else if (o >= 0) {
                    if (firstPts == Long.MIN_VALUE && info.size > 0) firstPts = info.presentationTimeUs
                    if (writer == null) {
                        readOutputFormat(c.outputFormat)
                        writer = PcmWriter(outFile, sampleRate, if (inChannels <= 2) inChannels else 1)
                    }
                    val w = writer!!
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(o)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        val samples: Int
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = ob.asFloatBuffer()
                            samples = fb.remaining()
                            if (conv.size < samples) conv = FloatArray(samples)
                            fb.get(conv, 0, samples)
                        } else {
                            val sb = ob.asShortBuffer()
                            samples = sb.remaining()
                            if (conv.size < samples) conv = FloatArray(samples)
                            for (k in 0 until samples) conv[k] = sb.get() / 32768f
                        }
                        if (inChannels <= 2) {
                            w.writeInterleaved(conv, samples - samples % inChannels)
                        } else {
                            val frames = samples / inChannels
                            for (f in 0 until frames) {
                                var s = 0f
                                for (ch in 0 until inChannels) s += conv[f * inChannels + ch]
                                conv[f] = s / inChannels
                            }
                            w.writeInterleaved(conv, frames)
                        }
                        if (durationUs > 0) progress((info.presentationTimeUs.toDouble() / durationUs).toFloat().coerceIn(0f, 1f))
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val w = writer ?: throw IOException("Декодер не выдал звук")
            writer = null
            val pcm = w.finish()
            if (pcm.frames == 0L) throw IOException("Файл пустой")
            progress(1f)
            return DecodedTrack(pcm, if (firstPts == Long.MIN_VALUE) 0L else firstPts)
        } catch (e: Throwable) {
            writer?.close()
            outFile.delete()
            throw e
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.close()
        }
    }
}
