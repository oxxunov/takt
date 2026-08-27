package com.tupicgames.takt.core.play

import com.tupicgames.takt.core.model.Beatmap
import com.tupicgames.takt.core.model.Judgement
import com.tupicgames.takt.core.model.Note
import com.tupicgames.takt.core.model.Windows
import kotlin.math.abs

/**
 * Состояние одной ноты. Рендер читает его, но не меняет.
 */
class NoteState(val note: Note) {
    // Пишет игровой цикл, читает поток отрисовки. Без @Volatile отрисовка
    // может ещё несколько кадров показывать уже отыгранную плитку.
    @Volatile
    var hit: Boolean = false
        internal set

    @Volatile
    var judged: Judgement? = null
        internal set

    /** Удержание взято и палец пока на экране. */
    @Volatile
    var holding: Boolean = false
        internal set

    /** До какого момента удержание доведено — для отрисовки остатка. */
    @Volatile
    var heldUntilSec: Float = 0f
        internal set
}

/** Итог партии. */
data class Result(
    val score: Long,
    val accuracy: Float,
    val maxCombo: Int,
    val perfect: Int,
    val good: Int,
    val miss: Int,
    val total: Int,
    val holdsCompleted: Int = 0,
    val holdsBroken: Int = 0
)

/**
 * Всё, что движок сообщает наружу. Раньше движок сам дёргал звук и рисование —
 * из-за этого его нельзя было запустить в тесте и нельзя было заменить
 * звуковой слой, не трогая логику.
 */
interface GameListener {
    fun onJudged(judgement: Judgement, lane: Int, songTimeSec: Double)
    fun onHoldCompleted(lane: Int, songTimeSec: Double)
    fun onFinished(result: Result)
}

/**
 * Логика партии. Не знает ни про Android, ни про звук, ни про отрисовку —
 * только время внутрь, события наружу. Поэтому тестируется на JVM.
 */
class GameEngine(val beatmap: Beatmap) {

    val notes: List<NoteState> = beatmap.notes.map { NoteState(it) }

    private val laneQueues: Array<ArrayDeque<Int>> =
        Array(beatmap.laneCount) { ArrayDeque() }

    var listener: GameListener? = null

    init {
        notes.forEachIndexed { i, ns ->
            val lane = ns.note.lane.coerceIn(0, beatmap.laneCount - 1)
            laneQueues[lane].addLast(i)
        }
    }

    var score: Long = 0; private set
    var combo: Int = 0; private set
    var maxCombo: Int = 0; private set
    var perfectCount: Int = 0; private set
    var goodCount: Int = 0; private set
    var missCount: Int = 0; private set
    var holdsCompleted: Int = 0; private set
    var holdsBroken: Int = 0; private set

    /** Активное удержание в каждой дорожке, если есть. */
    private val activeHold = arrayOfNulls<NoteState>(beatmap.laneCount)

    var lastJudgement: Judgement? = null; private set
    var lastJudgementAtSec: Double = -10.0; private set

    private var finished = false

    val totalNotes: Int get() = notes.size

    val accuracy: Float
        get() {
            val judged = perfectCount + goodCount + missCount + holdsBroken
            if (judged == 0) return 1f
            return (perfectCount + goodCount * 0.5f) / judged
        }

    /**
     * Продвинуть время. Вызывается игровым циклом, НЕ отрисовкой:
     * иначе пропущенные ноты зависят от того, успел ли кадр отрисоваться.
     */
    fun advanceTo(songTimeSec: Double) {
        if (finished) return

        // Удержания, доведённые до конца, засчитываются сами —
        // отпускать точно в конец от игрока требовать незачем.
        for (lane in activeHold.indices) {
            val ns = activeHold[lane] ?: continue
            ns.heldUntilSec = songTimeSec.toFloat()
            if (songTimeSec >= ns.note.endSec) {
                completeHold(lane, ns, songTimeSec)
            }
        }

        for (lane in laneQueues.indices) {
            val q = laneQueues[lane]
            while (q.isNotEmpty()) {
                val ns = notes[q.first()]
                if (songTimeSec - ns.note.timeSec <= Windows.MISS_SEC) break
                q.removeFirst()
                if (!ns.hit) registerMiss(ns, lane, songTimeSec)
            }
        }
        if (laneQueues.all { it.isEmpty() }) finish()
    }

    /**
     * @param songTimeSec время трека в момент касания, уже с поправкой калибровки
     * @return оценка, либо null если в окне не оказалось ноты
     */
    fun onTap(lane: Int, songTimeSec: Double): Judgement? {
        if (finished || lane !in laneQueues.indices) return null
        val q = laneQueues[lane]
        if (q.isEmpty()) return null

        val idx = q.first()
        val ns = notes[idx]
        val delta = abs(songTimeSec - ns.note.timeSec).toFloat()
        if (delta > Windows.GOOD_SEC) return null

        q.removeFirst()
        ns.hit = true
        val j = if (delta <= Windows.PERFECT_SEC) Judgement.PERFECT else Judgement.GOOD
        ns.judged = j

        if (j == Judgement.PERFECT) perfectCount++ else goodCount++
        combo++
        if (combo > maxCombo) maxCombo = combo

        // Удержание остаётся активным до отпускания или до своего конца.
        if (ns.note.isHold) {
            ns.holding = true
            ns.heldUntilSec = songTimeSec.toFloat()
            activeHold[lane] = ns
        }

        // Множитель ограничен сотней: без потолка счёт растёт экспоненциально
        // и ранние ошибки перестают что-либо значить.
        val multiplier = 1f + (combo.coerceAtMost(100) / 100f)
        score += (j.points * multiplier).toLong()

        lastJudgement = j
        lastJudgementAtSec = songTimeSec
        listener?.onJudged(j, lane, songTimeSec)
        return j
    }

    /**
     * Палец ушёл с дорожки.
     *
     * Отпускание в пределах запаса до конца засчитывается как завершённое
     * удержание: требовать попадания в конец с точностью до кадра — значит
     * наказывать за то, что физически не контролируется.
     */
    fun onRelease(lane: Int, songTimeSec: Double) {
        if (finished || lane !in activeHold.indices) return
        val ns = activeHold[lane] ?: return
        val remaining = ns.note.endSec - songTimeSec
        if (remaining <= Windows.HOLD_RELEASE_GRACE_SEC) {
            completeHold(lane, ns, songTimeSec)
        } else {
            breakHold(lane, ns, songTimeSec)
        }
    }

    private fun completeHold(lane: Int, ns: NoteState, songTimeSec: Double) {
        activeHold[lane] = null
        ns.holding = false
        ns.heldUntilSec = ns.note.endSec
        holdsCompleted++
        // Доведённое удержание добавляет очки пропорционально длине,
        // иначе длинные ноты стоят столько же, сколько короткое касание.
        score += (ns.note.holdSec * 120f).toLong()
        lastJudgement = Judgement.PERFECT
        lastJudgementAtSec = songTimeSec
        listener?.onHoldCompleted(lane, songTimeSec)
    }

    private fun breakHold(lane: Int, ns: NoteState, songTimeSec: Double) {
        activeHold[lane] = null
        ns.holding = false
        ns.heldUntilSec = songTimeSec.toFloat()
        ns.judged = Judgement.EARLY_RELEASE
        holdsBroken++
        combo = 0
        lastJudgement = Judgement.EARLY_RELEASE
        lastJudgementAtSec = songTimeSec
        listener?.onJudged(Judgement.EARLY_RELEASE, lane, songTimeSec)
    }

    /** Трек кончился раньше, чем закончились ноты (обрезанный файл и т.п.). */
    fun endEarly() = finish()

    private fun registerMiss(ns: NoteState, lane: Int, songTimeSec: Double) {
        ns.judged = Judgement.MISS
        missCount++
        combo = 0
        lastJudgement = Judgement.MISS
        lastJudgementAtSec = songTimeSec
        listener?.onJudged(Judgement.MISS, lane, songTimeSec)
    }

    private fun finish() {
        if (finished) return
        finished = true
        listener?.onFinished(result())
    }

    fun isFinished(): Boolean = finished

    fun result() = Result(
        score = score,
        accuracy = accuracy,
        maxCombo = maxCombo,
        perfect = perfectCount,
        good = goodCount,
        miss = missCount,
        total = totalNotes,
        holdsCompleted = holdsCompleted,
        holdsBroken = holdsBroken
    )
}
