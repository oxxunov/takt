package com.tupicgames.takt.core.play

import com.tupicgames.takt.core.generator.HopRoad
import com.tupicgames.takt.core.generator.HopTile
import com.tupicgames.takt.core.model.Judgement
import kotlin.math.abs

/**
 * Режим с прыгающим шариком.
 *
 * Шарик летит вперёд сам и приземляется на каждую плитку. Игрок ведёт его
 * только влево-вправо. Отсюда важное следствие: попадание зависит от
 * ПОЛОЖЕНИЯ пальца, а не от момента касания, поэтому задержка ввода и
 * смещение звука здесь не участвуют вовсе.
 *
 * Плитки стоят на долях трека, а не на нотах: ноты идут неравномерно, и
 * прыжки по ним были бы рваными. Выбор нот — самое ненадёжное место
 * системы — здесь не задействован совсем.
 *
 * Логика отделена от отрисовки: сюда подаётся время и положение пальца,
 * наружу уходят события. Поэтому режим проверяется тестами на JVM.
 */
class HopEngine(
    val road: HopRoad,
    /** Полуширина плитки в тех же единицах, что и x: попал, если ближе. */
    private val tileHalfWidth: Float = 0.22f
) {

    interface Listener {
        fun onLanded(tile: HopTile, index: Int, songTimeSec: Double)
        fun onFell(tile: HopTile, index: Int, songTimeSec: Double)
        fun onFinished(result: Result)
    }

    var listener: Listener? = null

    val tiles: List<HopTile> = road.tiles

    /** Положение шарика по ширине дороги: -1 крайняя левая, +1 крайняя правая. */
    @Volatile
    var ballX: Float = 0f
        private set

    var score: Long = 0; private set
    var combo: Int = 0; private set
    var maxCombo: Int = 0; private set
    var landed: Int = 0; private set

    /** Индекс следующей необработанной плитки. */
    var nextIndex: Int = 0; private set

    /** Забег окончен падением, а не концом трека. */
    @Volatile
    var fell: Boolean = false
        private set

    @Volatile
    var lastJudgement: Judgement? = null
        private set

    @Volatile
    var lastJudgementAtSec: Double = -10.0
        private set

    private var finished = false

    val accuracy: Float
        get() = if (tiles.isEmpty()) 1f else landed.toFloat() / tiles.size

    /** Ведём шарик пальцем. Значение зажимается краями дороги. */
    fun moveTo(x: Float) {
        if (finished) return
        ballX = x.coerceIn(-1f, 1f)
    }

    /**
     * Высота шарика в дуге между предыдущей и следующей плиткой, 0..1.
     *
     * Считается от времени, а не от кадров: при просадке кадров дуга
     * останется на месте, а не поедет.
     */
    fun hopHeight(songTimeSec: Double): Float {
        if (tiles.isEmpty()) return 0f
        val from = if (nextIndex > 0) tiles[nextIndex - 1].timeSec.toDouble()
        else songTimeSec - 0.4
        val to = if (nextIndex < tiles.size) tiles[nextIndex].timeSec.toDouble()
        else from + 0.4
        val span = to - from
        if (span <= 1e-4) return 0f
        val t = ((songTimeSec - from) / span).coerceIn(0.0, 1.0)
        // Парабола: 0 на краях, 1 в середине.
        return (4.0 * t * (1.0 - t)).toFloat()
    }

    /** Где дорога в данный момент — для плавного показа между плитками. */
    fun roadXAt(songTimeSec: Double): Float {
        if (tiles.isEmpty()) return 0f
        val i = nextIndex.coerceIn(0, tiles.size - 1)
        val prev = if (i > 0) tiles[i - 1] else tiles[0]
        val next = tiles[i]
        val span = next.timeSec - prev.timeSec
        if (span <= 1e-4f) return next.x
        val t = ((songTimeSec - prev.timeSec) / span).coerceIn(0.0, 1.0).toFloat()
        return prev.x + (next.x - prev.x) * t
    }

    /**
     * Продвинуть время. Обрабатывает все плитки, чей момент уже наступил.
     *
     * Первое же падение заканчивает забег — как в играх этого жанра.
     * Именно поэтому в генераторе есть жёсткий предел на сдвиг дороги:
     * без него уровень мог бы стать непроходимым не по вине игрока.
     */
    fun advanceTo(songTimeSec: Double) {
        if (finished) return
        while (nextIndex < tiles.size && songTimeSec >= tiles[nextIndex].timeSec) {
            val tile = tiles[nextIndex]
            val index = nextIndex
            nextIndex++

            if (abs(ballX - tile.x) <= tileHalfWidth) {
                landed++
                combo++
                if (combo > maxCombo) maxCombo = combo
                // Множитель ограничен сотней, иначе счёт растёт экспоненциально
                // и ранние ошибки перестают что-либо значить.
                val multiplier = 1f + (combo.coerceAtMost(100) / 100f)
                score += (100 * multiplier).toLong()
                lastJudgement = Judgement.PERFECT
                lastJudgementAtSec = songTimeSec
                listener?.onLanded(tile, index, songTimeSec)
            } else {
                fell = true
                combo = 0
                lastJudgement = Judgement.MISS
                lastJudgementAtSec = songTimeSec
                listener?.onFell(tile, index, songTimeSec)
                finish()
                return
            }
        }
        if (nextIndex >= tiles.size) finish()
    }

    fun endEarly() = finish()

    fun isFinished(): Boolean = finished

    private fun finish() {
        if (finished) return
        finished = true
        listener?.onFinished(result())
    }

    fun result() = Result(
        score = score,
        accuracy = accuracy,
        maxCombo = maxCombo,
        perfect = landed,
        good = 0,
        miss = if (fell) 1 else 0,
        total = tiles.size
    )
}
