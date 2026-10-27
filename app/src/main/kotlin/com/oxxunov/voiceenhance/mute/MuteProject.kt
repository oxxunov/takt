package com.oxxunov.voiceenhance.mute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Участок, где персонаж говорит: здесь голос будет удалён. Время — в миллисекундах видео. */
data class MuteRegion(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    /** Сколько голоса оставить, 0..0.3 (ползунок «Оставить голос»). */
    val keepVoice: Float = 0f,
) {
    val lengthMs: Long get() = endMs - startMs
}

/** Проект «Заглушки»: одно видео и размеченные участки. Сохраняется после каждого изменения. */
data class MuteProject(
    val id: String,
    val name: String,
    val uri: String,
    val durationMs: Long,
    val createdAt: Long,
    val updatedAt: Long,
    /** Где остановился — при открытии видео встаёт сюда. */
    val positionMs: Long = 0L,
    val regions: List<MuteRegion> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", id).put("name", name).put("uri", uri)
        .put("dur", durationMs).put("created", createdAt).put("updated", updatedAt)
        .put("pos", positionMs)
        .put("regions", JSONArray().apply {
            regions.forEach {
                put(JSONObject().put("id", it.id).put("s", it.startMs).put("e", it.endMs).put("k", it.keepVoice.toDouble()))
            }
        })

    companion object {
        fun fromJson(o: JSONObject): MuteProject {
            val arr = o.optJSONArray("regions") ?: JSONArray()
            val regions = (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                MuteRegion(r.getLong("id"), r.getLong("s"), r.getLong("e"), r.optDouble("k", 0.0).toFloat())
            }
            return MuteProject(
                id = o.getString("id"),
                name = o.optString("name", "Видео"),
                uri = o.getString("uri"),
                durationMs = o.optLong("dur", 0L),
                createdAt = o.optLong("created", 0L),
                updatedAt = o.optLong("updated", 0L),
                positionMs = o.optLong("pos", 0L),
                regions = regions.sortedBy { it.startMs },
            )
        }
    }
}

/**
 * Хранилище проектов: filesDir/mute/<id>/project.json и peaks.bin (огибающая звука для волны).
 * Запись атомарная (через временный файл), чтобы проект не портился, если приложение закрыли посреди записи.
 */
class MuteStore(context: Context) {
    private val root = File(context.filesDir, "mute").apply { mkdirs() }

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun list(): List<MuteProject> = root.listFiles()
        ?.mapNotNull { d -> runCatching { load(d.name) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun load(id: String): MuteProject? {
        val f = File(File(root, id), "project.json")
        if (!f.exists()) return null
        return MuteProject.fromJson(JSONObject(f.readText()))
    }

    fun save(p: MuteProject) {
        val d = dir(p.id)
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(p.toJson().toString())
        val dst = File(d, "project.json")
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    /** Огибающая: пары (min, max) на каждые 10 мс. */
    fun savePeaks(id: String, peaks: FloatArray) {
        val tmp = File(dir(id), "peaks.bin.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(peaks.size)
            for (v in peaks) out.writeFloat(v)
        }
        val dst = File(dir(id), "peaks.bin")
        dst.delete()
        tmp.renameTo(dst)
    }

    fun loadPeaks(id: String): FloatArray? {
        val f = File(File(root, id), "peaks.bin")
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                val n = inp.readInt()
                FloatArray(n) { inp.readFloat() }
            }
        }.getOrNull()
    }
}
