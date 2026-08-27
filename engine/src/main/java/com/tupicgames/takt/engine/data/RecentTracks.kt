package com.tupicgames.takt.engine.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentTrack(
    val uri: Uri,
    val title: String,
    val cacheKey: String,
    val bpm: Float,
    val durationSec: Float,
    val playedAt: Long
)

/**
 * Список недавних треков.
 *
 * Раньше каждый запуск начинался с выбора файла заново, хотя анализ уже
 * лежал в кэше. Здесь хранятся только ссылка и заголовок — сам файл
 * никуда не копируется.
 */
class RecentTracks(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun list(): List<RecentTrack> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentTrack(
                    uri = Uri.parse(o.getString("uri")),
                    title = o.getString("title"),
                    cacheKey = o.getString("key"),
                    bpm = o.optDouble("bpm", 0.0).toFloat(),
                    durationSec = o.optDouble("dur", 0.0).toFloat(),
                    playedAt = o.optLong("at", 0L)
                )
            }.sortedByDescending { it.playedAt }
        }.getOrDefault(emptyList())
    }

    fun add(track: RecentTrack) {
        // Один и тот же файл не должен размножаться в списке.
        val merged = (listOf(track) + list().filter { it.cacheKey != track.cacheKey })
            .take(MAX)
        val arr = JSONArray()
        for (t in merged) {
            arr.put(
                JSONObject()
                    .put("uri", t.uri.toString())
                    .put("title", t.title)
                    .put("key", t.cacheKey)
                    .put("bpm", t.bpm.toDouble())
                    .put("dur", t.durationSec.toDouble())
                    .put("at", t.playedAt)
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun remove(cacheKey: String) {
        val kept = list().filter { it.cacheKey != cacheKey }
        val arr = JSONArray()
        for (t in kept) {
            arr.put(
                JSONObject()
                    .put("uri", t.uri.toString()).put("title", t.title)
                    .put("key", t.cacheKey).put("bpm", t.bpm.toDouble())
                    .put("dur", t.durationSec.toDouble()).put("at", t.playedAt)
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "tracks"
        const val MAX = 50
    }
}
