package com.oxxunov.voiceenhance.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Кодирует PCM в AAC (M4A) или Opus (WEBM) системным кодером — для последующей вставки в видео. */
object AudioFileEncoder {
    fun encode(
        pcm: PcmFile,
        out: File,
        mime: String,
        kbps: Int,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        val sr = pcm.sampleRate
        val ch = pcm.channels
        val isOpus = mime == MediaFormat.MIMETYPE_AUDIO_OPUS
        val format = MediaFormat.createAudioFormat(mime, sr, ch).apply {
            if (!isOpus) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 16 * ch)
        }
        val codec = try {
            MediaCodec.createEncoderByType(mime)
        } catch (e: Exception) {
            throw IOException("На устройстве нет кодера $mime")
        }
        val muxer = MediaMuxer(
            out.absolutePath,
            if (isOpus) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
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
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    } else if (o >= 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !isConfig && started) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (started) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }
}
