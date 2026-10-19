package com.oxxunov.voiceenhance.ui

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.annotation.SuppressLint
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/** Уровень сжатия. Битрейт указан для 1080p 30 к/с и пересчитывается под размер кадра и частоту. */
enum class CompressLevel(val label: String, val bps1080: Int, val note: String) {
    QUALITY("Высокое качество", 5_000_000, "Почти без видимых потерь"),
    BALANCE("Баланс", 3_000_000, "Как у сервисов: 23 мин 1080p ≈ 550 МБ"),
    SMALL("Минимальный размер", 1_800_000, "Заметно мягче картинка в движении"),
}

/** Что известно о видео до сжатия. */
data class VideoMeta(
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val sizeBytes: Long,
    val fps: Float,
    val audioBps: Int,
) {
    /** Битрейт видео для уровня [l]. */
    fun videoBps(l: CompressLevel): Int {
        val px = (width.toLong() * height).coerceAtLeast(1L).toDouble() / (1920.0 * 1080.0)
        val fpsK = sqrt((if (fps > 0f) fps else 30f) / 30.0).coerceIn(1.0, 1.5)
        return (l.bps1080 * px * fpsK).toInt().coerceAtLeast(400_000)
    }

    /** Примерный размер результата, байт. */
    fun estimate(l: CompressLevel): Long = ((videoBps(l).toLong() + audioBps) * durationUs / 8_000_000L) + 200_000L
}

/**
 * Сжатие видео: Media3 Transformer (Google), аппаратный кодер телефона.
 * Видео перекодируется в H.265 (HEVC) — при том же качестве файл примерно вдвое меньше, чем в H.264;
 * если H.265-кодера нет, Transformer сам переходит на H.264. Звук копируется без изменений.
 * Разрешение и частота кадров сохраняются.
 */
@SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VideoCompressor {

    fun probe(context: Context, uri: Uri): VideoMeta {
        var w = 0
        var h = 0
        var dur = 0L
        try {
            MediaMetadataRetriever().apply {
                setDataSource(context, uri)
                w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                dur = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
                release()
            }
        } catch (e: Exception) {
            throw IOException("Не удалось прочитать видео")
        }
        if (w <= 0 || h <= 0) throw IOException("В файле нет видеодорожки")
        var fps = 0f
        var audioBps = 0
        try {
            val ex = MediaExtractor()
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) }
                }
                if (mime.startsWith("audio/")) {
                    audioBps += if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) else 160_000
                }
            }
            ex.release()
        } catch (_: Exception) {
        }
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
        return VideoMeta(w, h, dur, size, fps, audioBps)
    }

    /** Сжимает [uri] в [out] (MP4). [progress] 0..1. Отмена — через отмену корутины. */
    suspend fun compress(
        context: Context,
        uri: Uri,
        out: File,
        videoBps: Int,
        progress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        out.delete()
        val handler = Handler(Looper.getMainLooper())
        suspendCancellableCoroutine { cont ->
            val encoder = DefaultEncoderFactory.Builder(context.applicationContext)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(videoBps)
                        .setiFrameIntervalSeconds(2f)
                        .build()
                )
                .setEnableFallback(true)
                .build()
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        progress(1f)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        out.delete()
                        if (cont.isActive) cont.resumeWithException(
                            IOException("Не удалось сжать видео: ${exportException.errorCodeName}", exportException)
                        )
                    }
                })
                .build()
            val holder = ProgressHolder()
            poll = Runnable {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress((holder.progress / 100f).coerceIn(0f, 0.99f))
                }
                handler.postDelayed(poll, 500)
            }
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                    out.delete()
                }
            }
            transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(uri)).build(), out.absolutePath)
            handler.post(poll)
        }
    }
}
