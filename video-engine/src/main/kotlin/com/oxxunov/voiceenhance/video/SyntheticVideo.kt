package com.oxxunov.voiceenhance.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.oxxunov.voiceenhance.engine.ContainerRules
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Генератор тестовых видео для автотестов: H.264 320×240 30 fps (белая вспышка на [markSeconds])
 * и, по желанию, AAC-звук: тон 220 Гц («музыка») + гармонический «голос» + короткий щелчок на [markSeconds].
 * Вспышка и щелчок совпадают по времени — по ним проверяется синхронизация.
 */
object SyntheticVideo {
    const val W = 320
    const val H = 240
    const val FPS = 30

    fun create(
        context: Context,
        out: File,
        seconds: Double,
        withAudio: Boolean,
        sampleRate: Int = 48000,
        channels: Int = 2,
        markSeconds: Double = 2.0,
    ): File {
        val dir = out.absoluteFile.parentFile!!
        val videoOnly = if (withAudio) File(dir, "syn_v_${System.nanoTime()}.mp4") else out
        encodeVideo(videoOnly, seconds, markSeconds)
        if (!withAudio) return out
        val pcmFile = File(dir, "syn_a_${System.nanoTime()}.f32")
        val m4a = File(dir, "syn_a_${System.nanoTime()}.m4a")
        try {
            val n = (seconds * sampleRate).toInt()
            val mark = (markSeconds * sampleRate).toInt()
            val burst = sampleRate / 500 // 2 мс
            val x = Array(channels) { FloatArray(n) }
            for (i in 0 until n) {
                val t = i.toDouble() / sampleRate
                val music = 0.15 * sin(2 * PI * 220.0 * t)
                val env = 0.5 + 0.5 * sin(2 * PI * 3.0 * t)
                var voice = 0.0
                for (h in 1..10) voice += sin(2 * PI * 150.0 * h * t) / h
                voice *= 0.08 * env
                var v = music + voice
                if (i in mark until mark + burst) v += 0.7 * sin(2 * PI * 3000.0 * (i - mark) / sampleRate)
                for (c in 0 until channels) x[c][i] = v.toFloat()
            }
            PcmWriter(pcmFile, sampleRate, channels).use { it.write(x, 0, n) }
            AudioFileEncoder.encode(PcmFile(pcmFile, sampleRate, channels), m4a, MediaFormat.MIMETYPE_AUDIO_AAC, 192)
            VideoRemuxer.remux(
                context, Uri.fromFile(videoOnly), 0, 0, m4a, 0L, (seconds * 1e6).toLong(),
                ContainerRules.Container.MP4, out, {}, { false },
            )
        } finally {
            pcmFile.delete(); m4a.delete(); videoOnly.delete()
        }
        return out
    }

    private fun encodeVideo(out: File, seconds: Double, markSeconds: Double) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 800_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val frames = (seconds * FPS).toInt()
            val markFrame = (markSeconds * FPS).toInt()
            var fed = 0
            var inDone = false
            var outDone = false
            val info = MediaCodec.BufferInfo()
            while (!outDone) {
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val pts = fed * 1_000_000L / FPS
                        if (fed >= frames) {
                            enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            val img = enc.getInputImage(i)!!
                            val luma = if (fed == markFrame) 235 else 40 + (fed * 3) % 150
                            for ((p, plane) in img.planes.withIndex()) {
                                val value = if (p == 0) luma else 128
                                val pw = if (p == 0) W else W / 2
                                val ph = if (p == 0) H else H / 2
                                val buf = plane.buffer
                                for (y in 0 until ph) for (x in 0 until pw) {
                                    buf.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                                }
                            }
                            enc.queueInputBuffer(i, 0, W * H * 3 / 2, pts, 0)
                            fed++
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.outputFormat)
                    muxer.start()
                    started = true
                } else if (o >= 0) {
                    val ob = enc.getOutputBuffer(o)!!
                    val cfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !cfg && started) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
            try { if (started) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }
}
