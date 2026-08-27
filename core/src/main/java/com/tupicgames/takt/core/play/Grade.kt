package com.tupicgames.takt.core.play

/** Оценка за уровень. Порог по точности, как принято в жанре. */
enum class Grade(val title: String, val minAccuracy: Float) {
    S("S", 0.97f),
    A("A", 0.92f),
    B("B", 0.85f),
    C("C", 0.75f),
    D("D", 0.60f),
    F("F", 0f);

    companion object {
        fun of(accuracy: Float, missCount: Int, total: Int): Grade {
            // Полное прохождение без единого промаха всегда S, даже если
            // часть попаданий была на грани окна: чистый проход ценнее
            // формальной доли процента точности.
            if (missCount == 0 && total > 0) return S
            return values().first { accuracy >= it.minAccuracy }
        }
    }
}
