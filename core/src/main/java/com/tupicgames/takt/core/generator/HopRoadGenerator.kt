package com.tupicgames.takt.core.generator

import com.tupicgames.takt.core.model.Analysis
import com.tupicgames.takt.core.model.Onset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Одна плитка дороги. */
data class HopTile(
    /** Момент приземления, сек. */
    val timeSec: Float,
    /** Положение по ширине дороги: -1 крайняя левая, +1 крайняя правая. */
    val x: Float
)

data class HopRoad(
    val tiles: List<HopTile>,
    val durationSec: Float,
    /** Прыжков на долю: 2 — дважды за долю, 1 — каждую, 0.5 — через одну. */
    val hopsPerBeat: Float
) {
    val hopsPerSecond: Float
        get() = if (durationSec > 0f) tiles.size / durationSec else 0f
}

/**
 * Строит дорогу для режима с прыгающим шариком.
 *
 * Ключевое отличие от обычной раскладки: плитки ставятся на ДОЛИ, а не на
 * ноты. Ноты идут неравномерно — шестнадцатые, синкопы, паузы — и шарик по
 * ним прыгал бы рвано, а рулить рваным ритмом невозможно. Доли же у нас
 * находятся с точностью около 5 мс, и пульс получается ровным по построению.
 *
 * Заодно это обходит самое ненадёжное место системы: выбор нот здесь
 * не участвует вовсе.
 */
object HopRoadGenerator {

    /** Играбельный коридор частоты прыжков. */
    private const val MIN_HOPS_PER_SEC = 1.4f
    private const val MAX_HOPS_PER_SEC = 4.2f

    /**
     * Насколько дорога может сместиться вбок за один прыжок, в долях ширины.
     *
     * Это и есть граница честности уровня: за время прыжка палец физически
     * успевает пройти ограниченное расстояние. Хороший дизайнер соблюдает
     * это на слух, у нас записано числом.
     */
    private const val MAX_SHIFT_PER_HOP = 0.55f

    /** Ниже этой длительности прыжка ограничение ужесточается пропорционально. */
    private const val COMFORT_HOP_SEC = 0.42f

    /** Длина участка, на котором плотность решается целиком, сек. */
    private const val SECTION_SEC = 8f

    /** Какая доля долей внутри участка должна иметь серединку, чтобы дробить его. */
    private const val SECTION_DENSE_SHARE = 0.60f

    /** Насколько удар между долями может быть слабее удара на доле. */
    private const val SUBDIVISION_WEIGHT_SHARE = 0.35f

    fun generate(analysis: Analysis): HopRoad {
        val beats = analysis.beats
        if (beats.size < 4) {
            return HopRoad(emptyList(), analysis.durationSec, 1f)
        }

        // Частота прыжков собирается в два независимых шага.
        //
        // Раньше решение было одно на весь трек: если базовая частота вышла
        // «одна доля — один прыжок», посекционное дробление не срабатывало
        // вообще, даже там, где музыка его требовала.
        val hopTimes = buildHopTimes(analysis, beats)
        if (hopTimes.size < 2) return HopRoad(emptyList(), analysis.durationSec, 1f)

        val hopsPerBeat = hopTimes.size.toFloat() / beats.size
        val raw = musicalCurve(hopTimes, analysis.onsets)
        val energy = sectionEnergy(hopTimes, analysis)
        val tiles = layout(hopTimes, raw, energy)

        return HopRoad(tiles, analysis.durationSec, hopsPerBeat)
    }

    /**
     * Времена прыжков.
     *
     * Шаг 1 — прореживание: если долей больше, чем игрок успевает,
     * берём каждую вторую или третью.
     *
     * Шаг 2 — дробление ПО УЧАСТКАМ: во вступлении нот мало и плитки идут
     * редко, в припеве появляются восьмые — плитки сжимаются, и шарик
     * проносится через них быстрее. Точка между долями попадает в дорогу,
     * только если в звуке рядом действительно есть удар: механическая
     * серединка превращала любую музыку в равномерное тиканье.
     */
    private fun buildHopTimes(analysis: Analysis, beats: FloatArray): List<Float> {
        val span = beats.last() - beats.first()
        if (span <= 0f) return beats.toList()
        val beatsPerSec = (beats.size - 1) / span

        // Шаг 1: прореживание.
        val stride = kotlin.math.ceil(beatsPerSec / MAX_HOPS_PER_SEC).toInt().coerceAtLeast(1)
        val base = ArrayList<Float>(beats.size / stride + 1)
        var i = 0
        while (i < beats.size) { base.add(beats[i]); i += stride }
        if (base.size < 2) return beats.toList()
        val baseRate = beatsPerSec / stride

        // Шаг 2: дробление.
        if (baseRate * 2f > MAX_HOPS_PER_SEC) return base

        if (baseRate < MIN_HOPS_PER_SEC) {
            // Музыка не даёт повода дробить, но пульс слишком редкий, чтобы
            // играть. Здесь играбельность важнее точности: дробим механически.
            return subdivideAll(base)
        }

        val events = RhythmEventExtractor.extract(
            analysis, RhythmEventExtractor.preferTriplets(analysis)
        )
        if (events.isEmpty()) return base

        val offBeat = events.filter { !it.onBeat && abs(it.positionInBeat - 0.5f) < 0.12f }
        val minWeight = subdivisionThreshold(events)

        // Для каждой доли — есть ли под её серединкой годный удар.
        val hasMid = BooleanArray(base.size)
        var cursor = 0
        for (k in 0 until base.size - 1) {
            val from = base[k]
            val to = base[k + 1]
            while (cursor < offBeat.size && offBeat[cursor].timeSec < from) cursor++
            var j = cursor
            while (j < offBeat.size && offBeat[j].timeSec < to) {
                if (offBeat[j].weight >= minWeight) { hasMid[k] = true; break }
                j++
            }
        }

        val dense = denseSections(analysis.durationSec, base, hasMid)

        val out = ArrayList<Float>(base.size * 2)
        for (k in 0 until base.size - 1) {
            out.add(base[k])
            if (dense[sectionOf(base[k], dense.size)] && hasMid[k]) {
                // Ставим точно в середину доли, а не на сам удар: сетка
                // прыжков внутри участка должна оставаться равномерной.
                out.add((base[k] + base[k + 1]) / 2f)
            }
        }
        out.add(base.last())
        return out
    }

    /** Механическое дробление — только как запас по играбельности. */
    private fun subdivideAll(base: List<Float>): List<Float> {
        val out = ArrayList<Float>(base.size * 2)
        for (k in 0 until base.size - 1) {
            out.add(base[k])
            out.add((base[k] + base[k + 1]) / 2f)
        }
        out.add(base.last())
        return out
    }

    /**
     * Какие участки трека дробятся.
     *
     * Решение принимается на участок целиком, а не на каждую долю: если
     * дробить выборочно, интервал прыжка скачет то в полтакта, то в такт,
     * и играть невозможно. Одиночный выпадающий участок подтягивается
     * к соседям, чтобы режим не мигал.
     */
    private fun denseSections(
        durationSec: Float,
        base: List<Float>,
        hasMid: BooleanArray
    ): BooleanArray {
        val count = (durationSec / SECTION_SEC).toInt() + 1
        val total = IntArray(count)
        val withMid = IntArray(count)
        for (k in base.indices) {
            val sec = sectionOf(base[k], count)
            total[sec]++
            if (hasMid[k]) withMid[sec]++
        }

        val raw = BooleanArray(count) { sec ->
            total[sec] > 0 && withMid[sec].toFloat() / total[sec] >= SECTION_DENSE_SHARE
        }
        val smooth = raw.copyOf()
        for (sec in 1 until count - 1) {
            if (raw[sec] != raw[sec - 1] && raw[sec] != raw[sec + 1]) smooth[sec] = raw[sec - 1]
        }
        return smooth
    }

    private fun sectionOf(timeSec: Float, count: Int): Int =
        (timeSec / SECTION_SEC).toInt().coerceIn(0, count - 1)

    /**
     * Порог силы для подразделения — доля от типичного события НА ДОЛЕ.
     *
     * Медиана самих внесеточных событий не годится: она отсекает ровно
     * половину независимо от музыки, и дробление отменялось всегда.
     * Сравнение с силой доли отвечает на нужный вопрос: тянет ли удар
     * между долями на самостоятельное событие.
     */
    private fun subdivisionThreshold(events: List<RhythmEvent>): Float {
        val onBeat = events.filter { it.onBeat }.map { it.weight }.sorted()
        if (onBeat.isEmpty()) return Float.MAX_VALUE
        return SUBDIVISION_WEIGHT_SHARE * onBeat[onBeat.size / 2]
    }

    /**
     * Сырая музыкальная линия: куда «хочет» уйти дорога в каждый момент.
     *
     * Берётся тот же признак, что и для раскладки по дорожкам — высота тона
     * и басовость. Мелодия идёт вверх — дорога вправо, бас — влево.
     */
    private fun musicalCurve(hopTimes: List<Float>, onsets: List<Onset>): FloatArray {
        val out = FloatArray(hopTimes.size)
        if (onsets.isEmpty()) return out

        // Скор каждого удара и его перцентиль по всему треку: у трека с узким
        // спектром абсолютные значения ничего не говорят.
        val scores = FloatArray(onsets.size) { i ->
            val o = onsets[i]
            0.7f * ln((o.centroid + 20f).toDouble()).toFloat() + 0.3f * (1f - o.low) * 6f
        }
        val order = scores.indices.sortedBy { scores[it] }
        val rank = FloatArray(onsets.size)
        for ((r, idx) in order.withIndex()) {
            rank[idx] = if (onsets.size > 1) r.toFloat() / (onsets.size - 1) else 0.5f
        }

        // Для каждого прыжка — средний перцентиль ближайших ударов.
        var cursor = 0
        for (h in hopTimes.indices) {
            val t = hopTimes[h]
            val from = t - 0.25f
            val to = t + 0.25f
            while (cursor < onsets.size && onsets[cursor].timeSec < from) cursor++
            var sum = 0f
            var count = 0
            var k = cursor
            while (k < onsets.size && onsets[k].timeSec <= to) {
                sum += rank[k]; count++; k++
            }
            // Нет ударов рядом — держим прежнее направление, а не прыгаем в центр.
            out[h] = if (count > 0) (sum / count) * 2f - 1f
            else if (h > 0) out[h - 1] else 0f
        }
        return out
    }

    /**
     * Энергия участка, 0..1 — насколько широко вилять.
     *
     * В припеве дорога гуляет шире, в куплете идёт почти прямо. Именно это
     * делает дизайнер вручную, слушая трек.
     */
    private fun sectionEnergy(hopTimes: List<Float>, analysis: Analysis): FloatArray {
        val out = FloatArray(hopTimes.size) { 0.6f }
        val env = analysis.envelope
        val hz = analysis.envelopeHz
        if (env.isEmpty() || hz <= 0f) return out

        val points = env.size / 3
        for (h in hopTimes.indices) {
            // Усредняем по паре секунд вокруг: отдельный всплеск не должен
            // расширять дорогу на один прыжок.
            val from = ((hopTimes[h] - 1f) * hz).toInt().coerceIn(0, points - 1)
            val to = ((hopTimes[h] + 1f) * hz).toInt().coerceIn(0, points - 1)
            var sum = 0f
            var n = 0
            for (p in from..to) {
                sum += env[p * 3] * 0.5f + env[p * 3 + 1] * 0.3f + env[p * 3 + 2] * 0.2f
                n++
            }
            out[h] = if (n > 0) (sum / n).coerceIn(0f, 1f) else 0.6f
        }
        return out
    }

    /**
     * Раскладка по ширине с ограничением скорости.
     *
     * Дорога тянется к музыкальной линии, но за один прыжок не может уйти
     * дальше, чем игрок физически успевает довести палец. На коротких
     * прыжках предел ужимается пропорционально их длине.
     */
    private fun layout(hopTimes: List<Float>, curve: FloatArray, energy: FloatArray): List<HopTile> {
        val tiles = ArrayList<HopTile>(hopTimes.size)
        var x = 0f
        for (h in hopTimes.indices) {
            // Ширина коридора зависит от энергии участка.
            val amplitude = 0.35f + 0.65f * energy[h]
            val target = (curve[h] * amplitude).coerceIn(-1f, 1f)

            val hopSec = if (h > 0) hopTimes[h] - hopTimes[h - 1] else COMFORT_HOP_SEC
            val allowed = MAX_SHIFT_PER_HOP * min(1f, hopSec / COMFORT_HOP_SEC)

            val delta = target - x
            x += if (abs(delta) <= allowed) delta else allowed * (if (delta > 0) 1f else -1f)
            x = x.coerceIn(-1f, 1f)

            tiles.add(HopTile(hopTimes[h], x))
        }
        return tiles
    }

    /** Максимальный сдвиг между соседними плитками — для проверок. */
    fun maxShift(road: HopRoad): Float {
        var m = 0f
        for (i in 1 until road.tiles.size) {
            m = max(m, abs(road.tiles[i].x - road.tiles[i - 1].x))
        }
        return m
    }
}
