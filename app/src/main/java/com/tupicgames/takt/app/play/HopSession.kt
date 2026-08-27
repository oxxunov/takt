package com.tupicgames.takt.app.play

import android.view.Choreographer
import com.tupicgames.takt.core.generator.HopTile
import com.tupicgames.takt.core.model.Judgement
import com.tupicgames.takt.core.play.HopEngine
import com.tupicgames.takt.core.play.Result
import com.tupicgames.takt.engine.audio.ClickEngine
import com.tupicgames.takt.engine.audio.GameClock

/** Всё, что нужно отрисовке режима с шариком. Только чтение. */
interface HopRenderSource {
    fun songTimeSec(): Double
    val tiles: List<HopTile>
    fun nextIndex(): Int
    fun ballX(): Float
    fun hopHeight(songTimeSec: Double): Float
    /** Положение дороги в данный момент — для плавного показа между плитками. */
    fun roadXAt(songTimeSec: Double): Float
    /** Забег окончен падением. */
    fun fell(): Boolean
    fun score(): Long
    fun combo(): Int
    fun accuracy(): Float
    fun lastJudgement(): Judgement?
    fun lastJudgementAtSec(): Double
    fun progress(): Float
}

/** Куда уходит движение пальца. */
interface HopInputSink {
    fun moveTo(laneX: Float)
}

/**
 * Игровой цикл режима с шариком.
 *
 * Как и в основном режиме, логика идёт по Choreographer, а не внутри
 * отрисовки: иначе пропущенные плитки зависели бы от того, успел ли
 * отрисоваться кадр.
 */
class HopSession(
    private val engine: HopEngine,
    private val clock: GameClock,
    private val click: ClickEngine,
    private val calibrationOffsetSec: Float,
    private val onFinished: (Result) -> Unit
) : HopRenderSource, HopInputSink, HopEngine.Listener, Choreographer.FrameCallback {

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
        // Трек может кончиться раньше плиток — например, файл обрезан.
        if (!clock.isRunning() && !engine.isFinished()) engine.endEarly()
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------------------------------------------------------- HopRenderSource --

    override fun songTimeSec(): Double = clock.positionSec() - calibrationOffsetSec
    override val tiles: List<HopTile> get() = engine.tiles
    override fun nextIndex(): Int = engine.nextIndex
    override fun ballX(): Float = engine.ballX
    override fun hopHeight(songTimeSec: Double): Float = engine.hopHeight(songTimeSec)
    override fun roadXAt(songTimeSec: Double): Float = engine.roadXAt(songTimeSec)
    override fun fell(): Boolean = engine.fell
    override fun score(): Long = engine.score
    override fun combo(): Int = engine.combo
    override fun accuracy(): Float = engine.accuracy
    override fun lastJudgement(): Judgement? = engine.lastJudgement
    override fun lastJudgementAtSec(): Double = engine.lastJudgementAtSec

    override fun progress(): Float {
        val dur = engine.road.durationSec
        if (dur <= 0f) return 0f
        return (songTimeSec() / dur).toFloat().coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------- HopInputSink --

    override fun moveTo(laneX: Float) = engine.moveTo(laneX)

    // ---------------------------------------------------------- HopEngine.Listener --

    override fun onLanded(tile: HopTile, index: Int, songTimeSec: Double) {
        // Звук приземления убран намеренно.
        //
        // Плитки стоят на долях, поэтому щелчок звучал вместе с каждым ударом
        // музыки и превращался в постоянный стук поверх трека. В режиме
        // с плитками звук нужен — там он подтверждает попадание пальцем;
        // здесь попадание видно глазом, и озвучивать его незачем.
    }

    override fun onFell(tile: HopTile, index: Int, songTimeSec: Double) {
        // Падение заканчивает забег — отдельный звук не нужен,
        // экран результатов появится сразу следом.
    }

    override fun onFinished(result: Result) {
        stop()
        onFinished.invoke(result)
    }
}
