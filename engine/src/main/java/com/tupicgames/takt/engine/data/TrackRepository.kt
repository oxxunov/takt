package com.tupicgames.takt.engine.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tupicgames.takt.core.model.Analysis
import com.tupicgames.takt.engine.analysis.NativeAnalyzer
import com.tupicgames.takt.engine.audio.PcmDecoder

data class TrackInfo(val uri: Uri, val title: String, val sizeBytes: Long, val cacheKey: String)

sealed interface AnalysisOutcome {
    data class Ready(val analysis: Analysis, val fromCache: Boolean) : AnalysisOutcome
    data object NoBeats : AnalysisOutcome
    data class Failed(val message: String) : AnalysisOutcome
}

/**
 * Единственный вход к тяжёлой работе над файлом.
 *
 * Экраны раньше сами декодировали, звали анализатор и клали в кэш —
 * логика дублировалась и не имела общего места для отмены и ошибок.
 */
class TrackRepository(
    private val context: Context,
    private val cache: AnalysisCache
) {

    fun describe(uri: Uri): TrackInfo {
        var title = uri.lastPathSegment ?: "трек"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) c.getString(ni)?.let { title = it }
                    if (si >= 0) size = c.getLong(si)
                }
            }
        }
        return TrackInfo(uri, title, size, cache.keyFor(uri, size))
    }

    fun cached(info: TrackInfo): Analysis? = cache.load(info.cacheKey)

    /** Блокирующая. Вызывать с фонового диспетчера. */
    fun analyze(info: TrackInfo): AnalysisOutcome {
        cached(info)?.let { return AnalysisOutcome.Ready(it, fromCache = true) }
        return try {
            val pcm = PcmDecoder.decode(context, info.uri)
            val analysis = NativeAnalyzer.analyze(pcm.toMonoFloat(), pcm.sampleRate)
            if (analysis.onsets.isEmpty()) return AnalysisOutcome.NoBeats
            cache.save(info.cacheKey, analysis)
            AnalysisOutcome.Ready(analysis, fromCache = false)
        } catch (e: Throwable) {
            // Без имени класса message у многих исключений пустой,
            // и на экране остаётся бесполезное "неизвестная ошибка".
            AnalysisOutcome.Failed("${e.javaClass.simpleName}: ${e.message ?: "без описания"}")
        }
    }
}
