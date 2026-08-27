package com.tupicgames.takt.engine.data

import android.content.Context
import com.tupicgames.takt.core.model.Difficulty

/** Лучший результат по треку и сложности. */
data class Record(
    val score: Long,
    val accuracy: Float,
    val maxCombo: Int,
    val grade: String
)

/**
 * Рекорды и список сыгранных треков.
 *
 * Без них партия исчезает бесследно: сыграл — и ничего не осталось,
 * возвращаться не за чем.
 */
class Records(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("records", Context.MODE_PRIVATE)

    private fun key(cacheKey: String, difficulty: Difficulty) = "$cacheKey|${difficulty.name}"

    fun best(cacheKey: String, difficulty: Difficulty): Record? {
        val k = key(cacheKey, difficulty)
        if (!sp.contains("$k|score")) return null
        return Record(
            score = sp.getLong("$k|score", 0),
            accuracy = sp.getFloat("$k|acc", 0f),
            maxCombo = sp.getInt("$k|combo", 0),
            grade = sp.getString("$k|grade", "F") ?: "F"
        )
    }

    /** @return true, если результат побил прежний рекорд. */
    fun submit(
        cacheKey: String,
        difficulty: Difficulty,
        score: Long,
        accuracy: Float,
        maxCombo: Int,
        grade: String
    ): Boolean {
        val prev = best(cacheKey, difficulty)
        if (prev != null && prev.score >= score) return false
        val k = key(cacheKey, difficulty)
        sp.edit()
            .putLong("$k|score", score)
            .putFloat("$k|acc", accuracy)
            .putInt("$k|combo", maxCombo)
            .putString("$k|grade", grade)
            .apply()
        return true
    }

    fun clear() = sp.edit().clear().apply()
}
