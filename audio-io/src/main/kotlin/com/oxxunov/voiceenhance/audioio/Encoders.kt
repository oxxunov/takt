package com.oxxunov.voiceenhance.audioio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal object Mp3Native {
    val loadError: Throwable? = try {
        System.loadLibrary("mp3lame")
        System.loadLibrary("veaudio")
        null
    } catch (t: Throwable) {
        t
    }

    @JvmStatic external fun create(sampleRate: Int, channels: Int, kbps: Int): Long
    @JvmStatic external fun encode(h: Long, left: FloatArray, right: FloatArray?, n: Int): ByteArray?
    @JvmStatic external fun flush(h: Long): ByteArray?
    @JvmStatic external fun lameTag(h: Long): ByteArray?
    @JvmStatic external fun close(h: Long)
}

/** MP3 через LAME 3.100 (CBR, качество кодирования 2, LAME/Xing-тег для точной длительности). */
object Mp3Encoder {
    fun encode(pcm: PcmFile, out: File, kbps: Int, progress: (Float) -> Unit, isCancelled: () -> Boolean) {
        Mp3Native.loadError?.let { throw IOException("MP3-кодер недоступен: ${it.message}") }
        if (pcm.sampleRate !in setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)) {
            throw IOException("MP3 не поддерживает ${pcm.sampleRate} Гц — сохраните в WAV или M4A")
        }
        val ch = pcm.channels
        val h = Mp3Native.create(pcm.sampleRate, ch, kbps)
        if (h == 0L) throw IOException("Не удалось запустить MP3-кодер")
        try {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                val block = 8192
                val buf = Array(ch) { FloatArray(block) }
                val total = pcm.frames
                var done = 0L
                PcmReader(pcm).use { r ->
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        val n = r.read(buf, block)
                        if (n <= 0) break
                        for (c in 0 until ch) for (i in 0 until n) buf[c][i] = buf[c][i].coerceIn(-1f, 1f)
                        val bytes = Mp3Native.encode(h, buf[0], if (ch == 2) buf[1] else null, n)
                            ?: throw IOException("Ошибка MP3-кодера")
                        raf.write(bytes)
                        done += n
                        if (total > 0) progress((done.toDouble() / total).toFloat())
                    }
                }
                Mp3Native.flush(h)?.let { raf.write(it) }
                // тег пишется на место зарезервированного первого кадра
                Mp3Native.lameTag(h)?.let { tag ->
                    if (tag.isNotEmpty()) {
                        raf.seek(0)
                        raf.write(tag)
                    }
                }
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            Mp3Native.close(h)
        }
    }
}

/** AAC-LC в контейнере M4A через системный кодер Android (MediaCodec + MediaMuxer). */
object AacEncoder {
    fun encode(pcm: PcmFile, out: File, kbps: Int, progress: (Float) -> Unit, isCancelled: () -> Boolean) {
        val sr = pcm.sampleRate
        val ch = pcm.channels
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 16 * ch)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            var track = -1
            val info = MediaCodec.BufferInfo()
            val total = pcm.frames
            var fed = 0L
            var inputDone = false
            var outputDone = false
            val planar = Array(ch) { FloatArray(8192) }
            PcmReader(pcm).use { r ->
                while (!outputDone) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val ib = codec.getInputBuffer(i)!!
                            ib.clear()
                            ib.order(ByteOrder.nativeOrder())
                            val maxFrames = minOf(ib.capacity() / (2 * ch), planar[0].size)
                            val n = if (maxFrames > 0) r.read(planar, maxFrames) else 0
                            val pts = fed * 1_000_000L / sr
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val sb = ib.asShortBuffer()
                                for (k in 0 until n) for (c in 0 until ch) {
                                    sb.put((planar[c][k].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                                }
                                codec.queueInputBuffer(i, 0, n * ch * 2, pts, 0)
                                fed += n
                                if (total > 0) progress((fed.toDouble() / total).toFloat())
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = m.addTrack(codec.outputFormat)
                        m.start()
                        muxerStarted = true
                    } else if (o >= 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !isConfig && muxerStarted) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            m.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } catch (e: Throwable) {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
            muxer = null
            out.delete()
            throw e
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            muxer?.let {
                try { if (muxerStarted) it.stop() } catch (_: Exception) {}
                it.release()
            }
        }
    }
}
