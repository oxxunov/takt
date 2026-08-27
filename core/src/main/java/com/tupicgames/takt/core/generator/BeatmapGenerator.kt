package com.tupicgames.takt.core.generator

import com.tupicgames.takt.core.model.Analysis
import com.tupicgames.takt.core.model.Beatmap
import com.tupicgames.takt.core.model.Difficulty
import com.tupicgames.takt.core.model.Note
import com.tupicgames.takt.core.model.Onset
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BeatmapGenerator {

    /** Ниже этого порога уверенности темпа сетка недостоверна — играем «по факту». */
    private const val MIN_TEMPO_CONFIDENCE = 0.35f

    private const val GRID_SUBDIVISION = 4      // шестнадцатые
    private const val MAX_SNAP_ERROR_SEC = 0.055f

    /**
     * Порог силы, ниже которого удар не становится нотой.
     *
     * Без него квота плотности на разреженном треке добирает недостачу
     * шумом — и ноты появляются там, где в музыке ничего нет. Лучше отдать
     * уровень пореже заявленного, чем ровнее, но мимо музыки.
     */
    private const val MIN_STRENGTH = 0.16f
    private const val MIN_STRENGTH_RATIO = 0.35f

    /**
     * Отсев хвостов затухания.
     *
     * Бочка с подтяжкой тона и затухающий синт дают вторичные всплески
     * спектрального потока — детектор честно находит их как удары, но
     * играть по ним нельзя: в музыке там уже ничего не начинается.
     * Признак хвоста: удар вскоре после заметно более сильного.
     *
     * Параметры подобраны на треке с плывущим темпом: доля нот, стоящих
     * на реальных ударах, поднялась с 71% до 92% без потерь на средней
     * сложности.
     */
    private const val TAIL_WINDOW_SEC = 0.15f
    private const val TAIL_RATIO = 2.0f

    /**
     * Удержания.
     *
     * Нота становится удержанием, если звук после удара реально тянется
     * и до следующей ноты есть место. Иначе удержание некуда растянуть,
     * и получится нечитаемая мешанина коротких полосок.
     */
    private const val MIN_SUSTAIN_FOR_HOLD_SEC = 0.45f
    private const val MIN_HOLD_SEC = 0.35f
    private const val MAX_HOLD_SEC = 4.0f
    /** Зазор до следующей ноты — палец должен успеть уйти. */
    private const val HOLD_GAP_SEC = 0.14f

    /**
     * Потолок доли удержаний.
     *
     * В живой музыке тянется почти всё — пэды, бас, вокал. По одному порогу
     * длительности половина нот превращается в удержания, и поток становится
     * вязким. Профессиональные чартеры ставят удержания редко, как акцент,
     * поэтому берём только самые длинные звуки и не больше этой доли.
     */
    private const val MAX_HOLD_FRACTION = 0.18f

    /**
     * @param sensitivity множитель плотности, 0.5..1.5. Ползунок в UI:
     *   пользователь сам решает, насколько густо, без повторного анализа.
     */
    /**
     * @param holds включать ли удержания
     */
    fun generate(
        analysis: Analysis,
        difficulty: Difficulty,
        laneCount: Int = 4,
        sensitivity: Float = 1.0f,
        holds: Boolean = true
    ): Beatmap {
        if (analysis.onsets.isEmpty()) {
            return Beatmap(emptyList(), analysis.bpm, analysis.durationSec, laneCount, difficulty, false)
        }

        // Сетка строится по найденным долям. Формула "первая доля + k × шаг"
        // разъезжается на длинном треке, а массив долей идёт за реальным
        // звуком и потому остаётся точным до конца.
        val useGrid = analysis.hasBeatGrid && analysis.confidence >= MIN_TEMPO_CONFIDENCE

        // ---- Слой 1: привязка к ритмической сетке ----
        val candidates = if (useGrid) quantize(analysis) else analysis.onsets.toList()

        // ---- Слой 2: квота плотности ----
        val targetNps = (difficulty.targetNps * sensitivity).coerceIn(0.5f, 12f)
        val strong = dropTails(dropWeak(candidates))
        val kept = thin(strong, targetNps, difficulty.minGapSec)

        // ---- Слой 3: раскладка по дорожкам ----
        val laid = assignLanes(kept, laneCount)
        val notes = if (holds) addHolds(laid, kept) else laid

        return Beatmap(notes, analysis.bpm, analysis.durationSec, laneCount, difficulty, useGrid)
    }

    /**
     * Строит узлы сетки: доли плюс их подразделения.
     *
     * Интервал берётся между КАЖДОЙ парой соседних долей, а не из среднего
     * темпа — если музыка местами ускоряется, подразделения ускоряются
     * вместе с ней.
     */
    private fun gridPoints(beats: FloatArray): FloatArray {
        val out = FloatArray((beats.size - 1) * GRID_SUBDIVISION + 1)
        var i = 0
        for (b in 0 until beats.size - 1) {
            val from = beats[b]
            val span = beats[b + 1] - from
            for (k in 0 until GRID_SUBDIVISION) {
                out[i++] = from + span * k / GRID_SUBDIVISION
            }
        }
        out[i] = beats.last()
        return out
    }

    /**
     * Онсет, не попавший ни в один узел сетки с допуском, почти всегда артефакт
     * (хвост реверберации, модуляция внутри затухающей ноты). Такие отбрасываем —
     * это главный источник «каши» в автогенерированных уровнях.
     */
    private fun quantize(analysis: Analysis): List<Onset> {
        val grid = gridPoints(analysis.beats)
        if (grid.size < 2) return analysis.onsets.toList()

        // Допуск — половина местного шага, но не больше жёсткого потолка:
        // на медленном треке половина шестнадцатой это уже 90 мс, и туда
        // начинает попадать всё подряд.
        val slots = HashMap<Int, Onset>()
        for (o in analysis.onsets) {
            val k = nearestIndex(grid, o.timeSec)
            val gridTime = grid[k]
            val localStep = when {
                k + 1 < grid.size -> grid[k + 1] - gridTime
                k > 0 -> gridTime - grid[k - 1]
                else -> 0f
            }
            val tolerance = min(max(localStep, 1e-4f) * 0.5f, MAX_SNAP_ERROR_SEC)
            if (abs(o.timeSec - gridTime) > tolerance) continue
            if (gridTime < 0f) continue

            val snapped = o.copy(timeSec = gridTime)
            val prev = slots[k]
            if (prev == null || snapped.strength > prev.strength) slots[k] = snapped
        }
        return slots.values.sortedBy { it.timeSec }
    }

    /** Индекс ближайшего узла сетки — двоичным поиском, узлов тысячи. */
    private fun nearestIndex(grid: FloatArray, t: Float): Int {
        var lo = 0
        var hi = grid.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (grid[mid] < t) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(grid[lo - 1] - t) <= abs(grid[lo] - t)) return lo - 1
        return lo
    }

    /**
     * Отсев слабых ударов.
     *
     * Порог двойной: абсолютный и относительный к медиане трека. У тихой
     * записи всё слабое по абсолютной шкале, у громкой — наоборот, поэтому
     * одного порога недостаточно.
     */
    private fun dropWeak(candidates: List<Onset>): List<Onset> {
        if (candidates.isEmpty()) return candidates
        val sorted = candidates.map { it.strength }.sorted()
        val median = sorted[sorted.size / 2]
        val floor = max(MIN_STRENGTH, median * MIN_STRENGTH_RATIO)
        val kept = candidates.filter { it.strength >= floor }
        // Если порог срезал почти всё, значит трек ровный по громкости —
        // возвращаем как было, иначе уровень окажется пустым.
        return if (kept.size >= candidates.size / 5) kept else candidates
    }

    /** Убирает удары, которые на деле являются затуханием предыдущих. */
    private fun dropTails(candidates: List<Onset>): List<Onset> {
        if (candidates.size < 2) return candidates
        val out = ArrayList<Onset>(candidates.size)
        for (i in candidates.indices) {
            val o = candidates[i]
            var isTail = false
            var j = i - 1
            while (j >= 0 && o.timeSec - candidates[j].timeSec <= TAIL_WINDOW_SEC) {
                if (candidates[j].strength > o.strength * TAIL_RATIO) { isTail = true; break }
                j--
            }
            if (!isTail) out.add(o)
        }
        return out
    }

    /**
     * Прореживание жадное по силе, но с локальным ограничением:
     * глобальная сортировка «оставить N самых громких» выедает тихие куплеты
     * целиком, поэтому квота считается в скользящем окне.
     */
    private fun thin(candidates: List<Onset>, targetNps: Float, minGap: Float): List<Onset> {
        if (candidates.isEmpty()) return emptyList()

        val windowSec = 2.0f
        val baseQuota = targetNps * windowSec

        // Квота окна масштабируется по его энергии: с фиксированной квотой
        // тихий куплет и громкий припев получают поровну нот, и трек играется
        // ровно там, где должен нарастать.
        val windowEnergy = HashMap<Int, Float>()
        for (o in candidates) {
            val w = floor(o.timeSec / windowSec).toInt()
            windowEnergy[w] = (windowEnergy[w] ?: 0f) + o.strength
        }
        val meanEnergy = if (windowEnergy.isEmpty()) 0f
        else windowEnergy.values.sum() / windowEnergy.size

        val quotas = HashMap<Int, Int>()
        for ((w, e) in windowEnergy) {
            val factor = if (meanEnergy > 0f) (e / meanEnergy).coerceIn(0.5f, 1.6f) else 1f
            quotas[w] = (baseQuota * factor).roundToInt().coerceAtLeast(1)
        }

        val byStrength = candidates.sortedByDescending { it.strength }
        val accepted = ArrayList<Onset>(candidates.size)
        val windowCount = HashMap<Int, Int>()
        val acceptedTimes = ArrayList<Float>()

        for (o in byStrength) {
            val w = floor(o.timeSec / windowSec).toInt()
            val cur = windowCount[w] ?: 0
            if (cur >= (quotas[w] ?: baseQuota.roundToInt())) continue

            if (tooClose(acceptedTimes, o.timeSec, minGap)) continue

            accepted.add(o)
            windowCount[w] = cur + 1
            insertSorted(acceptedTimes, o.timeSec)
        }

        return accepted.sortedBy { it.timeSec }
    }

    private fun insertSorted(list: ArrayList<Float>, v: Float) {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid] < v) lo = mid + 1 else hi = mid
        }
        list.add(lo, v)
    }

    private fun tooClose(sorted: ArrayList<Float>, t: Float, minGap: Float): Boolean {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < t) lo = mid + 1 else hi = mid
        }
        if (lo < sorted.size && sorted[lo] - t < minGap) return true
        if (lo > 0 && t - sorted[lo - 1] < minGap) return true
        return false
    }

    /**
     * Превращает часть нот в удержания.
     *
     * Длина берётся по реальному звучанию, но обрезается до следующей ноты
     * с зазором: удержание, налезающее на соседнюю ноту, физически не сыграть.
     */
    private fun addHolds(notes: List<Note>, onsets: List<Onset>): List<Note> {
        if (notes.size != onsets.size) return notes

        // Сначала считаем, какой длины вышло бы удержание для каждой ноты.
        val candidate = FloatArray(notes.size)
        for (i in notes.indices) {
            val sustain = onsets[i].sustainSec
            if (sustain < MIN_SUSTAIN_FOR_HOLD_SEC) continue
            // Предел — ближайшая следующая нота в любой дорожке, иначе
            // удержание перекроет её визуально и его не сыграть.
            val nextTime = if (i + 1 < notes.size) notes[i + 1].timeSec else Float.MAX_VALUE
            val room = nextTime - notes[i].timeSec - HOLD_GAP_SEC
            val hold = minOf(sustain, room, MAX_HOLD_SEC)
            if (hold >= MIN_HOLD_SEC) candidate[i] = hold
        }

        // Оставляем только самые длинные и не больше потолка доли.
        val limit = (notes.size * MAX_HOLD_FRACTION).toInt()
        val chosen = HashSet<Int>()
        if (limit > 0) {
            candidate.indices
                .filter { candidate[it] > 0f }
                .sortedByDescending { candidate[it] }
                .take(limit)
                .forEach { chosen.add(it) }
        }

        return notes.mapIndexed { i, n ->
            if (i in chosen) n.copy(holdSec = candidate[i]) else n
        }
    }

    /**
     * Дорожка определяется «музыкальным» скором: высота тона (спектральный
     * центроид) плюс поправка на басовость. Бас уходит влево, верх — вправо,
     * мелодия рисуется по экрану — уровень перестаёт ощущаться случайным.
     *
     * Скор переводится в номер дорожки через перцентили, а не линейно:
     * иначе на треке с узким спектром все ноты слипаются в одну дорожку.
     */
    private fun assignLanes(onsets: List<Onset>, laneCount: Int): List<Note> {
        if (onsets.isEmpty()) return emptyList()

        val scores = FloatArray(onsets.size) { i ->
            val o = onsets[i]
            // Центроид логарифмируем: слух воспринимает высоту логарифмически.
            val pitch = kotlin.math.ln((o.centroid + 20f).toDouble()).toFloat()
            0.7f * pitch + 0.3f * (1f - o.low) * 6f
        }

        val order = scores.indices.sortedBy { scores[it] }
        val laneOf = IntArray(onsets.size)
        for ((rank, idx) in order.withIndex()) {
            val lane = (rank.toLong() * laneCount / onsets.size).toInt()
            laneOf[idx] = lane.coerceIn(0, laneCount - 1)
        }

        // Разведение повторов: подряд идущие ноты в одной дорожке читаются
        // как «залипание» и играются хуже, чем ступенька в соседнюю.
        val notes = ArrayList<Note>(onsets.size)
        var prevLane = -1
        var prevPrevLane = -1
        var prevTime = -10f
        for (i in onsets.indices) {
            var lane = laneOf[i]
            val t = onsets[i].timeSec
            val fast = t - prevTime < 0.22f
            if (lane == prevLane && (fast || prevLane == prevPrevLane)) {
                val dir = if (prevLane > prevPrevLane || prevPrevLane < 0) -1 else 1
                var alt = lane + dir
                if (alt !in 0 until laneCount) alt = lane - dir
                if (alt in 0 until laneCount) lane = alt
            }
            notes.add(Note(t, lane))
            prevPrevLane = prevLane
            prevLane = lane
            prevTime = t
        }
        return notes
    }
}
