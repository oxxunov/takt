package com.tupicgames.takt.core.generator

import com.tupicgames.takt.core.model.Analysis
import com.tupicgames.takt.core.model.BeatInfo
import com.tupicgames.takt.core.model.Onset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Кандидат на игровое событие: точка сетки с найденным рядом ударом.
 */
data class RhythmEvent(
    val timeSec: Float,
    /** Сила подтверждающего удара, 0..2. */
    val strength: Float,
    /** Насколько точно удар совпал с узлом сетки, 0..1. */
    val alignment: Float,
    /** Доля такта: 0 — сильная доля, 0.5 — восьмая между долями и так далее. */
    val positionInBeat: Float,
    /** Позиция в такте, если размер известен; иначе -1. */
    val positionInBar: Int,
    /** Событие стоит ровно на доле, а не на подразделении. */
    val onBeat: Boolean
) {
    /** Насколько событие заслуживает плитки. */
    val weight: Float get() = strength * alignment * (if (onBeat) 1.25f else 1f)
}

/**
 * Строит ритмические события из сетки долей и найденных ударов.
 *
 * Главное отличие от простого дробления доли: точка подразделения
 * становится событием, ТОЛЬКО если рядом в звуке действительно есть удар.
 * Механическая серединка между долями превращала любую музыку в
 * равномерное тиканье — плитки шли через равные промежутки независимо
 * от того, что играет.
 */
object RhythmEventExtractor {

    /**
     * Допуски по стадиям.
     *
     * Для привязки события к сетке допуск должен быть заметно жёстче, чем
     * при поиске темпа: там 60 мс помогают не потерять кандидата, здесь
     * такой разброс означал бы плитку, стоящую мимо слышимого удара.
     */
    const val BEAT_TOLERANCE_SEC = 0.035f
    const val SUBDIVISION_TOLERANCE_SEC = 0.025f

    /** Рассматриваемые дробления доли. */
    private val STRAIGHT_DIVISIONS = floatArrayOf(0.5f, 0.25f, 0.75f)
    private val TRIPLET_DIVISIONS = floatArrayOf(1f / 3f, 2f / 3f)

    /** Минимальная сила удара, чтобы он породил событие вне доли. */
    private const val MIN_SUBDIVISION_STRENGTH = 0.28f

    /**
     * @param triplets искать ли триольные подразделения вместо прямых
     */
    fun extract(analysis: Analysis, triplets: Boolean = false): List<RhythmEvent> {
        val beats = analysis.beatInfo
        if (beats.size < 2 || analysis.onsets.isEmpty()) return emptyList()

        val onsets = analysis.onsets
        val out = ArrayList<RhythmEvent>(beats.size * 2)
        val divisions = if (triplets) TRIPLET_DIVISIONS else STRAIGHT_DIVISIONS

        for (i in 0 until beats.size - 1) {
            val beat = beats[i]
            val span = beats[i + 1].timeSec - beat.timeSec
            if (span <= 1e-4f) continue

            // Сама доля — событие всегда, даже если удар слабый: пульс
            // должен читаться, иначе дорога рассыпается на островки.
            val onBeatHit = nearestOnset(onsets, beat.timeSec, BEAT_TOLERANCE_SEC)
            out.add(
                RhythmEvent(
                    timeSec = beat.timeSec,
                    strength = onBeatHit?.strength ?: (0.35f * beat.confidence),
                    alignment = alignmentOf(onBeatHit, beat.timeSec, BEAT_TOLERANCE_SEC),
                    positionInBeat = 0f,
                    positionInBar = beat.positionInBar.takeIf { beat.barIndex >= 0 } ?: -1,
                    onBeat = true
                )
            )

            // Подразделения — только при реальном ударе рядом.
            for (d in divisions) {
                val t = beat.timeSec + span * d
                val hit = nearestOnset(onsets, t, SUBDIVISION_TOLERANCE_SEC) ?: continue
                if (hit.strength < MIN_SUBDIVISION_STRENGTH) continue
                out.add(
                    RhythmEvent(
                        timeSec = t,
                        strength = hit.strength,
                        alignment = alignmentOf(hit, t, SUBDIVISION_TOLERANCE_SEC),
                        positionInBeat = d,
                        positionInBar = -1,
                        onBeat = false
                    )
                )
            }
        }

        out.sortBy { it.timeSec }
        return out
    }

    /**
     * Прямая сетка или триольная — решается по тому, где больше ударов.
     *
     * Свинговую и триольную музыку прямая сетка режет: удары стоят на
     * третях доли, а мы ищем их на половинах и не находим.
     */
    fun preferTriplets(analysis: Analysis): Boolean {
        val straight = extract(analysis, triplets = false).count { !it.onBeat }
        val triplet = extract(analysis, triplets = true).count { !it.onBeat }
        return triplet > straight * 1.25
    }

    private fun nearestOnset(onsets: List<Onset>, t: Float, tolerance: Float): Onset? {
        var lo = 0
        var hi = onsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (onsets[mid].timeSec < t) lo = mid + 1 else hi = mid
        }
        var best: Onset? = null
        var bestD = tolerance
        for (k in max(0, lo - 2)..min(onsets.size - 1, lo + 2)) {
            val d = abs(onsets[k].timeSec - t)
            if (d <= bestD) { bestD = d; best = onsets[k] }
        }
        return best
    }

    private fun alignmentOf(hit: Onset?, t: Float, tolerance: Float): Float {
        if (hit == null) return 0.4f
        return (1f - abs(hit.timeSec - t) / tolerance).coerceIn(0f, 1f)
    }
}
