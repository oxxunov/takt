package com.oxxunov.voiceenhance.mute

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.oxxunov.voiceenhance.audioio.AudioImporter
import com.oxxunov.voiceenhance.audioio.Demuxers
import com.oxxunov.voiceenhance.engine.ContainerRules
import com.oxxunov.voiceenhance.engine.FrameSource
import com.oxxunov.voiceenhance.engine.MuteMixer
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Дорожки файла: видео (может не быть — тогда это аудиофайл) и первая звуковая. */
data class MediaInfo(
    val durationUs: Long,
    val videoTrack: Int,
    val videoMime: String,
    val rotation: Int,
    val audioTrack: Int,
    val audioMime: String,
)

object MediaTools {

    fun probe(context: Context, uri: Uri): MediaInfo {
        val ex = try {
            Demuxers.open(context, uri)
        } catch (e: Exception) {
            throw IOException("Файл не открывается: удалён, перемещён или формат не поддерживается")
        }
        var vt = -1
        var vMime = ""
        var rot = 0
        var at = -1
        var aMime = ""
        var dur = 0L
        try {
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/") && vt < 0) {
                    vt = i
                    vMime = mime
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) rot = f.getInteger(MediaFormat.KEY_ROTATION)
                } else if (mime.startsWith("audio/") && at < 0) {
                    at = i
                    aMime = mime
                }
            }
        } finally {
            ex.close()
        }
        if (at < 0) throw IOException("В файле нет звука")
        try {
            MediaMetadataRetriever().apply {
                setDataSource(context, uri)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { dur = maxOf(dur, it * 1000) }
                if (rot == 0 && vt >= 0) rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                release()
            }
        } catch (_: Exception) {
        }
        return MediaInfo(dur, vt, vMime, rot, at, aMime)
    }

    /** Кодирует звук в AAC (M4A) или Opus (WEBM) системным кодером. */
    fun encode(
        src: FrameSource,
        out: File,
        mime: String,
        kbps: Int,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        val sr = src.sampleRate
        val ch = src.channels
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
            val total = src.frames
            var fed = 0L
            var inputDone = false
            var outputDone = false
            val planar = Array(ch) { FloatArray(8192) }
            while (!outputDone) {
                if (isCancelled()) throw ProcessingCancelledException()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val ib = codec.getInputBuffer(i)!!
                        ib.clear()
                        ib.order(ByteOrder.nativeOrder())
                        val maxFrames = minOf(ib.capacity() / (2 * ch), planar[0].size)
                        val n = if (maxFrames > 0) src.read(planar, maxFrames) else 0
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

    /**
     * Замена звука без перекодирования видео: кадры видео копируются как есть,
     * новая дорожка ставится на время первого сэмпла исходной — синхронизация сохраняется.
     * Возвращает время последнего записанного звукового пакета.
     */
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
    ): Long {
        val vex = Demuxers.open(context, source)
        val aex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
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
            var aLast = 0L
            while (!vDone || !aDone) {
                if (isCancelled()) throw ProcessingCancelledException()
                val vTime = if (vDone) Long.MAX_VALUE else vex.sampleTime.let { if (it < 0) Long.MAX_VALUE else it }
                val aTime = if (aDone) Long.MAX_VALUE else aex.sampleTime.let { if (it < 0) Long.MAX_VALUE else it + audioStartUs }
                if (vTime == Long.MAX_VALUE) vDone = true
                if (aTime == Long.MAX_VALUE) aDone = true
                if (vDone && aDone) break
                val useVideo = !vDone && (aDone || vTime <= aTime)
                buf.clear()
                val size = if (useVideo) vex.readSampleData(buf, 0) else aex.readSampleData(buf, 0)
                if (size < 0) {
                    if (useVideo) vDone = true else aDone = true
                    continue
                }
                val sf = if (useVideo) vex.sampleFlags else aex.sampleFlags
                val flags = if (sf and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, maxOf(0L, if (useVideo) vTime else aTime), flags)
                m.writeSampleData(if (useVideo) vt else at, buf, info)
                if (!useVideo) aLast = maxOf(aLast, info.presentationTimeUs)
                if (useVideo) vex.advance() else aex.advance()
                if (useVideo && durationUs > 0) progress((vTime.toDouble() / durationUs).toFloat().coerceIn(0f, 1f))
            }
            progress(1f)
            return aLast
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
            vex.close()
            aex.release()
        }
    }

    private val delayCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Задержка «кодер + декодер» на этом устройстве, в сэмплах: кодируем одиночный импульс,
     * декодируем и ищем, где он оказался. Без этого звук сдвинулся бы на ~40 мс относительно губ.
     */
    fun encoderDelay(context: Context, mime: String, sr: Int, ch: Int, kbps: Int, workDir: File): Int {
        val key = "$mime/$sr/$ch/$kbps"
        delayCache[key]?.let { return it }
        val len = sr * 2
        val at = sr / 2
        val pcmFile = File(workDir, "cal.f32")
        val enc = File(workDir, "cal." + if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) "webm" else "m4a")
        val dec = File(workDir, "cal_dec.f32")
        val result = try {
            val buf = Array(ch) { FloatArray(len) }
            for (c in 0 until ch) buf[c][at] = 0.8f
            PcmWriter(pcmFile, sr, ch).use { it.write(buf, 0, len) }
            MuteMixer.open(PcmFile(pcmFile, sr, ch), emptyList(), emptyList(), 0).use { encode(it, enc, mime, kbps) }
            val back = AudioImporter(context).decodeTrack(Uri.fromFile(enc), 0, dec).pcm
            val y = Array(back.channels) { FloatArray(back.frames.toInt()) }
            PcmReader(back).use { it.read(y, y[0].size) }
            var best = 0
            var bestV = 0f
            for (i in y[0].indices) if (abs(y[0][i]) > bestV) { bestV = abs(y[0][i]); best = i }
            val d = best - at
            if (bestV > 0.1f && d in 0..8192) d else 0
        } catch (_: Exception) {
            0
        } finally {
            pcmFile.delete(); enc.delete(); dec.delete()
        }
        delayCache[key] = result
        return result
    }
}
