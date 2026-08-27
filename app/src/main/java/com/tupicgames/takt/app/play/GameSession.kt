package com.tupicgames.takt.app.play

import android.os.SystemClock
import android.view.Choreographer
import com.tupicgames.takt.core.model.Judgement
import com.tupicgames.takt.core.play.GameEngine
import com.tupicgames.takt.core.play.GameListener
import com.tupicgames.takt.core.play.NoteState
import com.tupicgames.takt.core.play.Result
import com.tupicgames.takt.engine.audio.ClickEngine
import com.tupicgames.takt.engine.audio.GameClock

/** Всё, что нужно отрисовке. Рендер читает это и ничего не меняет. */
interface RenderSource {
    fun songTimeSec(): Double
    val notes: List<NoteState>
    val laneCount: Int
    val score: Long
    val combo: Int
    val accuracy: Float
    val lastJudgement: Judgement?
    val lastJudgementAtSec: Double
    fun laneFlashAtSec(lane: Int): Double
    /** Оценка последнего попадания по дорожке — для цвета вспышки. */
    fun laneJudgementAt(lane: Int): Judgement?
    /** Доля пройденного трека, 0..1 — для полосы прогресса. */
    fun progress(): Float
    /** Уровень полосы спектра в текущий момент, 0..1. 0 — низ, 1 — середина, 2 — верх. */
    fun envelopeAt(band: Int): Float
}

/** Куда вид отправляет касания. */
interface InputSink {
    /**
     * @param eventTimeMillis SystemClock.uptimeMillis() из MotionEvent —
     *   не момент обработки, а момент самого касания
     */
    fun onTap(lane: Int, eventTimeMillis: Long)

    /** Палец ушёл с дорожки — нужно для удержаний. */
    fun onRelease(lane: Int, eventTimeMillis: Long)
}

/**
 * Игровой цикл.
 *
 * Раньше движок обновлялся внутри draw(): пропущенные ноты зависели от
 * того, успел ли отрисоваться кадр, а слой отрисовки напрямую дёргал звук.
 * Теперь логика идёт по Choreographer, отрисовка — своим темпом, а звук
 * подключён к движку через GameListener.
 */
class GameSession(
    private val engine: GameEngine,
    private val clock: GameClock,
    private val click: ClickEngine,
    private val calibrationOffsetSec: Float,
    /** Огибающая спектра тройками (низ, середина, верх). */
    private val envelope: FloatArray,
    private val envelopeHz: Float,
    private val onFinished: (Result) -> Unit
) : RenderSource, InputSink, GameListener, Choreographer.FrameCallback {

    private val flashAt = DoubleArray(engine.beatmap.laneCount) { -10.0 }
    private val flashJudgement = arrayOfNulls<Judgement>(engine.beatmap.laneCount)
    private var running = false

    init {
        engine.listener = this
    }

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        engine.listener = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        engine.advanceTo(songTimeSec())
        // Трек может кончиться раньше нот — например, файл обрезан.
        if (!clock.isRunning() && !engine.isFinished()) engine.endEarly()
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    // ------------------------------------------------------- RenderSource --

    override fun songTimeSec(): Double = clock.positionSec() - calibrationOffsetSec

    override val notes: List<NoteState> get() = engine.notes
    override val laneCount: Int get() = engine.beatmap.laneCount
    override val score: Long get() = engine.score
    override val combo: Int get() = engine.combo
    override val accuracy: Float get() = engine.accuracy
    override val lastJudgement: Judgement? get() = engine.lastJudgement
    override val lastJudgementAtSec: Double get() = engine.lastJudgementAtSec

    override fun laneFlashAtSec(lane: Int): Double =
        if (lane in flashAt.indices) flashAt[lane] else -10.0

    override fun laneJudgementAt(lane: Int): Judgement? =
        if (lane in flashJudgement.indices) flashJudgement[lane] else null

    override fun progress(): Float {
        val dur = engine.beatmap.durationSec
        if (dur <= 0f) return 0f
        return (songTimeSec() / dur).toFloat().coerceIn(0f, 1f)
    }

    override fun envelopeAt(band: Int): Float {
        if (envelope.isEmpty() || envelopeHz <= 0f || band !in 0..2) return 0f
        val idx = (songTimeSec() * envelopeHz).toInt()
        val base = idx * 3 + band
        if (base < 0 || base >= envelope.size) return 0f
        return envelope[base]
    }

    // ---------------------------------------------------------- InputSink --

    override fun onTap(lane: Int, eventTimeMillis: Long) {
        val now = songTimeSec()
        if (lane in flashAt.indices) flashAt[lane] = now
        engine.onTap(lane, now - inputAge(eventTimeMillis))
    }

    override fun onRelease(lane: Int, eventTimeMillis: Long) {
        engine.onRelease(lane, songTimeSec() - inputAge(eventTimeMillis))
    }

    /**
     * Возраст события ввода в секундах.
     *
     * Очередь ввода добавляет несколько миллисекунд — в ритм-игре это
     * разница между PERFECT и GOOD, поэтому берём время самого события,
     * а не момент, когда до него дошли руки.
     */
    private fun inputAge(eventTimeMillis: Long): Double =
        (SystemClock.uptimeMillis() - eventTimeMillis).coerceAtLeast(0L) / 1000.0

    // -------------------------------------------------------- GameListener --

    override fun onJudged(judgement: Judgement, lane: Int, songTimeSec: Double) {
        if (lane in flashJudgement.indices) {
            flashJudgement[lane] = judgement
            // Промах не игрок нажал — вспышку по его дорожке рисуем тоже,
            // иначе не видно, где именно потерялась нота.
            if (judgement == Judgement.MISS) flashAt[lane] = songTimeSec
        }
        if (judgement != Judgement.MISS) click.hit()
    }

    override fun onHoldCompleted(lane: Int, songTimeSec: Double) {
        if (lane in flashAt.indices) {
            flashAt[lane] = songTimeSec
            flashJudgement[lane] = Judgement.PERFECT
        }
        click.hit()
    }

    override fun onFinished(result: Result) {
        stop()
        onFinished.invoke(result)
    }
}
