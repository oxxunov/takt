package com.oxxunov.voiceenhance.audioio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Чтение контейнера (MP4, MKV, WEBM, 3GP, …).
 *
 * Основной движок — разборщики ExoPlayer (MediaExtractorCompat): они читают фрагментированные MP4,
 * файлы с несколькими частями и прочие варианты, на которых системный MediaExtractor
 * на части телефонов видит только начало файла. Если ExoPlayer формат не знает — системный.
 */
interface Demuxer : Closeable {
    val engine: String
    val trackCount: Int
    fun getTrackFormat(index: Int): MediaFormat
    fun selectTrack(index: Int)
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int
    val sampleTime: Long
    val sampleFlags: Int
    fun advance(): Boolean
}

object Demuxers {
    fun open(context: Context, uri: Uri): Demuxer {
        val compat = try {
            CompatDemuxer.open(context, uri)
        } catch (_: Exception) {
            null
        }
        if (compat != null && compat.trackCount > 0) return compat
        compat?.close()
        return PlatformDemuxer.open(context, uri)
    }
}

@SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class CompatDemuxer(private val ex: MediaExtractorCompat) : Demuxer {
    override val engine = "ExoPlayer"
    override val trackCount get() = ex.trackCount
    override fun getTrackFormat(index: Int): MediaFormat = ex.getTrackFormat(index)
    override fun selectTrack(index: Int) = ex.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer, offset: Int) = ex.readSampleData(buffer, offset)
    override val sampleTime get() = ex.sampleTime
    override val sampleFlags get() = ex.sampleFlags
    override fun advance() = ex.advance()
    override fun close() = ex.release()

    companion object {
        fun open(context: Context, uri: Uri): CompatDemuxer {
            val ex = MediaExtractorCompat(context)
            try {
                ex.setDataSource(uri, 0L)
            } catch (e: Exception) {
                ex.release()
                throw e
            }
            return CompatDemuxer(ex)
        }
    }
}

private class PlatformDemuxer(private val ex: MediaExtractor) : Demuxer {
    override val engine = "Android"
    override val trackCount get() = ex.trackCount
    override fun getTrackFormat(index: Int): MediaFormat = ex.getTrackFormat(index)
    override fun selectTrack(index: Int) = ex.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer, offset: Int) = ex.readSampleData(buffer, offset)
    override val sampleTime get() = ex.sampleTime
    override val sampleFlags get() = ex.sampleFlags
    override fun advance() = ex.advance()
    override fun close() = ex.release()

    companion object {
        fun open(context: Context, uri: Uri): PlatformDemuxer {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
            } catch (e: Exception) {
                ex.release()
                throw e
            }
            return PlatformDemuxer(ex)
        }
    }
}
