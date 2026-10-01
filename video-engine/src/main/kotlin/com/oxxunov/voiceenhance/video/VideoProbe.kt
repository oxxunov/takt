package com.oxxunov.voiceenhance.video

import android.content.Context
import com.oxxunov.voiceenhance.audioio.Demuxers
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

data class AudioTrackInfo(
    val index: Int,
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val language: String?,
)

data class VideoInfo(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val sizeBytes: Long,
    val videoTrack: Int,
    val videoMime: String,
    val audioTracks: List<AudioTrackInfo>,
    /** Каким движком читается файл: ExoPlayer или Android. */
    val engine: String = "",
)

object VideoProbe {
    /** Число сэмплов дорожки и время последнего (по самим данным, а не по заголовку). */
    fun trackStats(context: Context, uri: Uri, track: Int): Pair<Int, Long> {
        val ex = Demuxers.open(context, uri)
        try {
            ex.selectTrack(track)
            var n = 0
            var last = 0L
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                n++
                if (t > last) last = t
                if (!ex.advance()) break
            }
            return n to last
        } finally {
            ex.close()
        }
    }

    fun probe(context: Context, uri: Uri): VideoInfo {
        val ex = try {
            Demuxers.open(context, uri)
        } catch (e: Exception) {
            throw IOException("Файл повреждён или формат не поддерживается")
        }
        try {
            var videoTrack = -1
            var videoMime = ""
            var width = 0
            var height = 0
            var rotation = 0
            var duration = 0L
            val audio = ArrayList<AudioTrackInfo>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i
                    videoMime = mime
                    width = f.getInteger(MediaFormat.KEY_WIDTH)
                    height = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) rotation = f.getInteger(MediaFormat.KEY_ROTATION)
                } else if (mime.startsWith("audio/")) {
                    audio += AudioTrackInfo(
                        index = i,
                        mime = mime,
                        sampleRate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0,
                        channels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0,
                        language = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null,
                    )
                }
            }
            if (videoTrack < 0) throw IOException("В файле нет видеодорожки")
            // длительность по заголовку файла (системное чтение метаданных)
            try {
                MediaMetadataRetriever().apply {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                        duration = maxOf(duration, it * 1000)
                    }
                    release()
                }
            } catch (_: Exception) {
            }
            if (rotation == 0) {
                // в части файлов поворот есть только в метаданных контейнера
                try {
                    MediaMetadataRetriever().apply {
                        setDataSource(context, uri)
                        rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        release()
                    }
                } catch (_: Exception) {
                }
            }
            val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
            return VideoInfo(duration, width, height, rotation, size, videoTrack, videoMime, audio, ex.engine)
        } finally {
            ex.close()
        }
    }
}
