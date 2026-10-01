package com.oxxunov.voiceenhance.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.oxxunov.voiceenhance.engine.ContainerRules
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import java.io.File
import java.nio.ByteBuffer

/**
 * Замена звука без перекодирования видео: кадры видео копируются как есть (remux),
 * новая аудиодорожка ставится на время первого сэмпла исходной — синхронизация сохраняется.
 */
object VideoRemuxer {
    /** Что реально записано в файл. */
    class Stats(val videoSamples: Int, val videoLastUs: Long, val audioSamples: Int, val audioLastUs: Long)

    fun remux(
        context: Context,
        source: Uri,
        videoTrack: Int,
        rotation: Int,
        encodedAudio: File,
        audioStartUs: Long,
        durationUs: Long,
        container: ContainerRules.Container,
        out: File,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Stats {
        val vex = MediaExtractor()
        val aex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vex.setDataSource(context, source, null)
            vex.selectTrack(videoTrack)
            aex.setDataSource(encodedAudio.absolutePath)
            aex.selectTrack(0)
            val m = MediaMuxer(
                out.absolutePath,
                if (container == ContainerRules.Container.WEBM) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = m
            if (container == ContainerRules.Container.MP4 && rotation != 0) m.setOrientationHint(rotation)
            val vFormat = vex.getTrackFormat(videoTrack)
            val vt = m.addTrack(vFormat)
            val at = m.addTrack(aex.getTrackFormat(0))
            m.start()
            started = true

            val cap = maxOf(
                if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
                16 * 1024 * 1024,
            )
            val buf = ByteBuffer.allocateDirect(cap)
            val info = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            var vCount = 0
            var aCount = 0
            var vLast = 0L
            var aLast = 0L
            while (!vDone || !aDone) {
                if (isCancelled()) throw ProcessingCancelledException()
                val vTime = if (vDone) Long.MAX_VALUE else vex.sampleTime.let { if (it < 0) Long.MAX_VALUE else it }
                val aTime = if (aDone) Long.MAX_VALUE else aex.sampleTime.let { if (it < 0) Long.MAX_VALUE else it + audioStartUs }
                if (vTime == Long.MAX_VALUE) vDone = true
                if (aTime == Long.MAX_VALUE) aDone = true
                if (vDone && aDone) break
                val useVideo = !vDone && (aDone || vTime <= aTime)
                val ex = if (useVideo) vex else aex
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) {
                    if (useVideo) vDone = true else aDone = true
                    continue
                }
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, maxOf(0L, if (useVideo) vTime else aTime), flags)
                m.writeSampleData(if (useVideo) vt else at, buf, info)
                if (useVideo) { vCount++; vLast = maxOf(vLast, info.presentationTimeUs) }
                else { aCount++; aLast = maxOf(aLast, info.presentationTimeUs) }
                ex.advance()
                if (useVideo && durationUs > 0) progress((vTime.toDouble() / durationUs).toFloat().coerceIn(0f, 1f))
            }
            progress(1f)
            return Stats(vCount, vLast, aCount, aLast)
        } catch (e: Throwable) {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
            muxer = null
            out.delete()
            throw e
        } finally {
            muxer?.let {
                try { if (started) it.stop() } catch (_: Exception) {}
                it.release()
            }
            vex.release()
            aex.release()
        }
    }
}
