package com.tupicgames.takt.core.model

/** Обнаруженный удар. Всё, что анализатор знает про одно событие в звуке. */
data class Onset(
    val timeSec: Float,
    /** ~1.0 у типичного сильного удара трека. */
    val strength: Float,
    /** Доли энергии по полосам, в сумме 1. */
    val low: Float,
    val mid: Float,
    val high: Float,
    /** Спектральный центроид в Гц — прокси высоты тона. */
    val centroid: Float,
    /**
     * Сколько звук держится после удара, сек.
     *
     * Короткий перкуссионный удар даёт около нуля, тянущаяся нота —
     * сотни миллисекунд. По этому полю генератор решает, делать ли
     * ноту удержанием.
     */
    val sustainSec: Float = 0f
)

/** Результат анализа файла. Кэшируется; из него генерируются раскладки. */
data class Analysis(
    /** Средний темп — только для показа пользователю. */
    val bpm: Float,
    val firstBeatSec: Float,
    val durationSec: Float,
    val confidence: Float,
    val onsets: List<Onset>,
    /**
     * Времена всех долей трека.
     *
     * Именно по ним строится сетка, а не по формуле "первая доля + k × шаг".
     * Живая музыка плывёт, и ошибка темпа в 0.2 BPM за четыре минуты уводит
     * жёсткую сетку на сотни миллисекунд. Здесь каждая доля найдена там,
     * где реально звучит, поэтому расхождение не копится.
     */
    val beats: FloatArray = FloatArray(0),
    /**
     * Огибающая спектра по трём полосам (низ, середина, верх), 0..1,
     * тройками с шагом [envelopeHz]. Используется для реактивного фона:
     * в игре звук уже декодирован в PCM и по спектру не разбирается.
     */
    val envelope: FloatArray = FloatArray(0),
    val envelopeHz: Float = 0f,
    /**
     * Те же доли, что в [beats], но с музыкальным контекстом.
     * Массив [beats] оставлен как есть ради совместимости с генераторами.
     */
    val beatInfo: List<BeatInfo> = emptyList(),
    /** Размер такта: 4 или 3; 0 — определить не удалось. */
    val meter: Int = 0,
    /** Разброс темпа по треку, BPM. Большой означает плавающий темп. */
    val tempoSpread: Float = 0f
) {
    val beatSec: Float get() = if (bpm > 1f) 60f / bpm else 0.5f

    /** Есть ли надёжная ритмическая сетка. */
    val hasBeatGrid: Boolean get() = beats.size >= 4

    // beats — массив, поэтому equals/hashCode приходится писать руками:
    // сгенерированные сравнивали бы ссылки.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Analysis) return false
        return bpm == other.bpm && firstBeatSec == other.firstBeatSec &&
            durationSec == other.durationSec && confidence == other.confidence &&
            onsets == other.onsets && beats.contentEquals(other.beats) &&
            envelope.contentEquals(other.envelope) && envelopeHz == other.envelopeHz &&
            beatInfo == other.beatInfo && meter == other.meter
    }

    override fun hashCode(): Int {
        var r = bpm.hashCode()
        r = 31 * r + firstBeatSec.hashCode()
        r = 31 * r + durationSec.hashCode()
        r = 31 * r + confidence.hashCode()
        r = 31 * r + onsets.hashCode()
        r = 31 * r + beats.contentHashCode()
        r = 31 * r + envelope.contentHashCode()
        r = 31 * r + envelopeHz.hashCode()
        r = 31 * r + beatInfo.hashCode()
        r = 31 * r + meter
        return r
    }
}

/**
 * Доля с музыкальным контекстом.
 *
 * Помимо времени несёт уверенность, силу и положение в такте. Генератор
 * может по ним решать, где ставить плитки: сильная доля такта — очевидный
 * кандидат, слабая с низкой уверенностью — сомнительный.
 */
data class BeatInfo(
    val timeSec: Float,
    /** Насколько уверенно доля опирается на реальный звук, 0..1. */
    val confidence: Float,
    /** Сила доли: энергия удара с упором на низ. */
    val strength: Float,
    /** Номер такта; -1, если размер определить не удалось. */
    val barIndex: Int,
    /** Позиция внутри такта: 0 — сильная доля. */
    val positionInBar: Int
) {
    val isDownbeat: Boolean get() = barIndex >= 0 && positionInBar == 0
}

data class Note(
    val timeSec: Float,
    val lane: Int,
    /** Длительность удержания, сек. 0 — обычное касание. */
    val holdSec: Float = 0f
) {
    val isHold: Boolean get() = holdSec > 0f
    val endSec: Float get() = timeSec + holdSec
}

data class Beatmap(
    val notes: List<Note>,
    val bpm: Float,
    val durationSec: Float,
    val laneCount: Int,
    val difficulty: Difficulty,
    /** false — темп не найден, ноты стоят по факту, а не по сетке. */
    val quantized: Boolean
) {
    val notesPerSecond: Float
        get() = if (durationSec > 0f) notes.size / durationSec else 0f
}

enum class Difficulty(
    val title: String,
    val targetNps: Float,
    val minGapSec: Float
) {
    EASY("Лёгкая", 1.6f, 0.26f),
    NORMAL("Средняя", 2.8f, 0.17f),
    HARD("Сложная", 4.2f, 0.115f),
    EXPERT("Эксперт", 6.0f, 0.085f)
}

enum class Judgement(val title: String, val points: Int) {
    PERFECT("PERFECT", 300),
    GOOD("GOOD", 100),
    /** Удержание взято, но отпущено раньше конца. */
    EARLY_RELEASE("РАНО", 50),
    MISS("MISS", 0)
}

object Windows {
    const val PERFECT_SEC = 0.045f
    const val GOOD_SEC = 0.095f
    /** После этого нота снимается с дорожки как пропущенная. */
    const val MISS_SEC = 0.140f

    /**
     * Насколько раньше конца можно отпустить удержание без штрафа.
     * Без запаса удержание почти невозможно завершить чисто: палец
     * уходит с экрана на несколько кадров раньше визуального конца.
     */
    const val HOLD_RELEASE_GRACE_SEC = 0.12f
}
