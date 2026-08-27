package com.tupicgames.takt.engine.data

import android.content.Context
import com.tupicgames.takt.core.model.Difficulty

/** Пользовательские настройки. Один экземпляр на процесс, живёт в AppGraph. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("takt", Context.MODE_PRIVATE)

    /** Поправка калибровки, мс. Положительная — игрок систематически опаздывает. */
    var offsetMs: Int
        get() = sp.getInt(KEY_OFFSET, 0)
        set(v) = sp.edit().putInt(KEY_OFFSET, v.coerceIn(-300, 300)).apply()

    var calibrated: Boolean
        get() = sp.getBoolean(KEY_CALIBRATED, false)
        set(v) = sp.edit().putBoolean(KEY_CALIBRATED, v).apply()

    var difficulty: Difficulty
        get() = runCatching {
            Difficulty.valueOf(sp.getString(KEY_DIFFICULTY, null) ?: Difficulty.NORMAL.name)
        }.getOrDefault(Difficulty.NORMAL)
        set(v) = sp.edit().putString(KEY_DIFFICULTY, v.name).apply()

    /** Множитель плотности нот в процентах, 50..150. */
    var sensitivityPercent: Int
        get() = sp.getInt(KEY_SENSITIVITY, 100)
        set(v) = sp.edit().putInt(KEY_SENSITIVITY, v.coerceIn(50, 150)).apply()

    /**
     * Дополнительное смещение для конкретного трека, мс.
     *
     * Общая калибровка снимает задержку устройства, но у каждого файла своя
     * пауза в начале и своя точность кодирования. Именно так решают этот
     * вопрос профессиональные ритм-игры: ползунок смещения на песню.
     */
    fun trackOffsetMs(cacheKey: String): Int = sp.getInt("$KEY_TRACK_OFFSET$cacheKey", 0)

    fun setTrackOffsetMs(cacheKey: String, value: Int) =
        sp.edit().putInt("$KEY_TRACK_OFFSET$cacheKey", value.coerceIn(-500, 500)).apply()

    /**
     * Режим игры.
     *
     * false — плитки падают, игрок отбивает ритм касаниями (по умолчанию).
     * true  — шарик прыгает сам, игрок только ведёт его влево-вправо.
     */
    var hopMode: Boolean
        get() = sp.getBoolean(KEY_HOP_MODE, false)
        set(v) = sp.edit().putBoolean(KEY_HOP_MODE, v).apply()

    /** Включать ли удержания в раскладку. */
    var holdsEnabled: Boolean
        get() = sp.getBoolean(KEY_HOLDS, true)
        set(v) = sp.edit().putBoolean(KEY_HOLDS, v).apply()

    /** Время полёта ноты до линии попадания, мс. */
    var approachMs: Int
        get() = sp.getInt(KEY_APPROACH, 1150)
        set(v) = sp.edit().putInt(KEY_APPROACH, v.coerceIn(500, 2500)).apply()

    private companion object {
        const val KEY_OFFSET = "offset_ms"
        const val KEY_CALIBRATED = "calibrated"
        const val KEY_DIFFICULTY = "difficulty"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_APPROACH = "approach_ms"
        const val KEY_HOLDS = "holds_enabled"
        const val KEY_TRACK_OFFSET = "track_offset_"
        const val KEY_HOP_MODE = "hop_mode"
    }
}
