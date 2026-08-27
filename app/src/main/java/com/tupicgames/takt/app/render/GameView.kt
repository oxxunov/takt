package com.tupicgames.takt.app.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.tupicgames.takt.app.play.InputSink
import com.tupicgames.takt.app.play.RenderSource
import com.tupicgames.takt.core.model.Judgement
import kotlin.math.max
import kotlin.random.Random

/**
 * Только отрисовка и ввод.
 *
 * Никакой игровой логики и никакого звука: состояние берётся из RenderSource,
 * касания уходят в InputSink. Поэтому отрисовку можно тормозить, ронять кадры
 * и вообще выключать — на судейство это не влияет.
 */
class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private var source: RenderSource? = null
    private var input: InputSink? = null

    /** Сколько секунд нота летит от верха экрана до линии попадания. */
    var approachSec: Float = 1.15f

    private var thread: Thread? = null
    @Volatile private var rendering = false
    private var firstVisible = 0

    private val bgPaint = Paint().apply { color = Color.parseColor("#0B0D14") }
    private val lanePaint = Paint().apply { color = Color.parseColor("#121722") }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#232B3D"); strokeWidth = 1.5f
    }
    private val hitLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A6B90"); strokeWidth = 5f
    }
    private val hitGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint().apply { color = Color.parseColor("#3D7BFF") }
    private val progressBgPaint = Paint().apply { color = Color.parseColor("#1B2233") }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 44f; isFakeBoldText = true
    }
    private val judgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 66f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val comboPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 110f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val comboLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66748F"); textSize = 26f
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private val laneColors = intArrayOf(
        Color.parseColor("#3D7BFF"),
        Color.parseColor("#00C2A8"),
        Color.parseColor("#FFB020"),
        Color.parseColor("#FF5C8A")
    )

    private val rect = RectF()
    // Какой палец на какой дорожке. Без этого отпускание не привязать
    // к дорожке: MotionEvent при ACTION_UP уже не даёт исходную позицию.
    private val pointerLane = HashMap<Int, Int>()
    private var lowShader: Shader? = null
    private var midShader: Shader? = null
    private var highShader: Shader? = null
    private var pulseShadersForHeight = -1f

    // Частицы попадания. Пул фиксированного размера: выделять объекты
    // в цикле отрисовки нельзя — сборщик мусора даст рывки на каждом кадре.
    private val particles = Array(MAX_PARTICLES) { Particle() }
    private var particleCursor = 0
    private val rnd = Random(1)

    private class Particle {
        var active = false
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var born = 0.0
        var color = Color.WHITE
    }

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun attach(source: RenderSource, input: InputSink) {
        this.source = source
        this.input = input
        firstVisible = 0
        pointerLane.clear()
        for (p in particles) p.active = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        rendering = true
        thread = Thread(this, "game-render").apply { start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        rendering = false
        thread?.join(800)
        thread = null
    }

    override fun run() {
        while (rendering) {
            // Пустой continue крутил бы цикл на полной загрузке ядра, пока
            // поверхность недоступна — отдаём квант и пробуем снова.
            val canvas = try { holder.lockCanvas() } catch (e: Exception) { null }
            if (canvas == null) {
                try { Thread.sleep(4) } catch (e: InterruptedException) { return }
                continue
            }
            try {
                renderFrame(canvas)
            } catch (e: Throwable) {
                // Кадр не обязан быть идеальным. Исключение в отрисовке
                // не должно убивать процесс вместе с партией.
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    private fun renderFrame(canvas: Canvas) {
        val src = source
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        if (src == null || w <= 0f || h <= 0f) return

        val lanes = max(1, src.laneCount)
        val laneW = w / lanes
        val hitY = h * 0.84f
        val t = src.songTimeSec()

        drawLanes(canvas, src, lanes, laneW, w, h, hitY, t)
        // Строго после полос дорожек: раньше заливка чётных дорожек
        // закрывала свечение, и получался шахматный обрыв.
        drawBackgroundPulse(canvas, src, w, h)
        drawHitLine(canvas, w, hitY)
        drawNotes(canvas, src, lanes, laneW, h, hitY, t)
        drawParticles(canvas, t)
        drawHud(canvas, src, w, h, t)
        drawProgress(canvas, src, w, h)
    }

    private fun drawLanes(
        canvas: Canvas, src: RenderSource, lanes: Int,
        laneW: Float, w: Float, h: Float, hitY: Float, t: Double
    ) {
        for (i in 0 until lanes) {
            if (i % 2 == 0) canvas.drawRect(i * laneW, 0f, (i + 1) * laneW, h, lanePaint)
            if (i > 0) canvas.drawLine(i * laneW, 0f, i * laneW, h, separatorPaint)

            val since = t - src.laneFlashAtSec(i)
            if (since in 0.0..FLASH_SEC) {
                val fade = (1.0 - since / FLASH_SEC).toFloat()
                val c = when (src.laneJudgementAt(i)) {
                    Judgement.MISS -> Color.parseColor("#FF4D6D")
                    Judgement.EARLY_RELEASE -> Color.parseColor("#FF8A3D")
                    Judgement.GOOD -> Color.parseColor("#FFC24A")
                    else -> laneColors[i % laneColors.size]
                }
                flashPaint.shader = null
                flashPaint.color = Color.argb(
                    (fade * 110).toInt(), Color.red(c), Color.green(c), Color.blue(c)
                )
                canvas.drawRect(i * laneW, hitY - h * 0.30f, (i + 1) * laneW, h, flashPaint)
            }
        }
    }

    /**
     * Фон дышит вместе с музыкой.
     *
     * Геометрия свечений неподвижна, меняется только прозрачность.
     * Двигать границы нельзя: край уезжает по экрану и читается как
     * отдельный объект, а не как свечение.
     *
     * Данные готовы заранее: разбирать спектр в игре нечем — звук
     * уже декодирован в PCM и по полосам не раскладывается.
     */
    private fun drawBackgroundPulse(canvas: Canvas, src: RenderSource, w: Float, h: Float) {
        val low = src.envelopeAt(0)
        val mid = src.envelopeAt(1)
        val high = src.envelopeAt(2)
        if (low + mid + high <= 0.01f) return

        ensurePulseShaders(h)

        // Прозрачность модулируется музыкой, границы размыты градиентом —
        // резкий прямоугольник выглядел полосой, а не свечением.
        lowShader?.let {
            bgPulsePaint.shader = it
            bgPulsePaint.alpha = (low * 60f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, h * 0.55f, w, h, bgPulsePaint)
        }
        midShader?.let {
            bgPulsePaint.shader = it
            bgPulsePaint.alpha = (mid * 34f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, h * 0.18f, w, h * 0.62f, bgPulsePaint)
        }
        highShader?.let {
            bgPulsePaint.shader = it
            bgPulsePaint.alpha = (high * 42f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h * 0.22f, bgPulsePaint)
        }
        bgPulsePaint.shader = null
        bgPulsePaint.alpha = 255
    }

    /** Градиенты строятся один раз на размер: создавать их каждый кадр — мусор и рывки. */
    private fun ensurePulseShaders(h: Float) {
        if (pulseShadersForHeight == h) return
        pulseShadersForHeight = h

        val clear = Color.TRANSPARENT
        lowShader = LinearGradient(
            0f, h * 0.55f, 0f, h,
            clear, Color.parseColor("#3D7BFF"), Shader.TileMode.CLAMP
        )
        // Середина гаснет в обе стороны, иначе видно верхнюю границу.
        midShader = LinearGradient(
            0f, h * 0.18f, 0f, h * 0.62f,
            intArrayOf(clear, Color.parseColor("#00C2A8"), clear),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        highShader = LinearGradient(
            0f, 0f, 0f, h * 0.22f,
            Color.parseColor("#FFB020"), clear, Shader.TileMode.CLAMP
        )
    }

    private fun drawHitLine(canvas: Canvas, w: Float, hitY: Float) {
        hitGlowPaint.color = Color.parseColor("#223D7BFF")
        canvas.drawRect(0f, hitY - 14f, w, hitY + 14f, hitGlowPaint)
        canvas.drawLine(0f, hitY, w, hitY, hitLinePaint)
    }

    private fun drawNotes(
        canvas: Canvas, src: RenderSource, lanes: Int,
        laneW: Float, h: Float, hitY: Float, t: Double
    ) {
        val noteH = h * 0.048f
        val pxPerSec = (hitY + noteH) / approachSec
        val notes = src.notes

        // Нижняя граница окна двигается вперёд и не сбрасывается: иначе
        // каждый кадр пришлось бы пробегать все отыгранные ноты, а их тысячи.
        while (firstVisible < notes.size &&
            notes[firstVisible].note.timeSec - t < -0.25
        ) firstVisible++

        for (i in firstVisible until notes.size) {
            val ns = notes[i]
            val note = ns.note
            val dt = note.timeSec - t
            if (dt > approachSec) break
            // Взятое удержание продолжаем рисовать, пока палец на экране.
            if (ns.hit && !ns.holding) continue
            val y = hitY - dt * pxPerSec
            if (!note.isHold && y < -noteH) continue

            val lane = note.lane.coerceIn(0, lanes - 1)
            val color = laneColors[lane % laneColors.size]
            val pad = laneW * 0.09f
            val closeness = (1f - (dt / approachSec).toFloat()).coerceIn(0f, 1f)

            if (note.isHold) {
                // Хвост удержания: от головы до конца, длина в тех же
                // пикселях на секунду, что и скорость падения.
                val endDt = note.endSec - t
                val yEnd = hitY - endDt * pxPerSec
                if (yEnd > h) continue
                val tailTop = yEnd.toFloat().coerceAtLeast(-noteH)
                val tailBottom = if (ns.holding) hitY else y.toFloat()
                if (tailBottom > tailTop) {
                    rect.set(
                        lane * laneW + laneW * 0.22f, tailTop,
                        (lane + 1) * laneW - laneW * 0.22f, tailBottom
                    )
                    holdPaint.color = color
                    holdPaint.alpha = if (ns.holding) 220 else (90 + 90 * closeness).toInt()
                    canvas.drawRoundRect(rect, laneW * 0.1f, laneW * 0.1f, holdPaint)
                }
            }

            if (!ns.hit) {
                rect.set(
                    lane * laneW + pad, (y - noteH).toFloat(),
                    (lane + 1) * laneW - pad, y.toFloat()
                )
                notePaint.color = color
                notePaint.alpha = (140 + 115 * closeness).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rect, noteH * 0.30f, noteH * 0.30f, notePaint)

                noteEdgePaint.color = Color.argb(
                    (200 * closeness).toInt().coerceIn(0, 255), 255, 255, 255
                )
                canvas.drawRoundRect(rect, noteH * 0.30f, noteH * 0.30f, noteEdgePaint)
            }
        }
    }

    /** Вызывается отрисовкой при попадании: брызги от линии. */
    private fun spawnParticles(x: Float, y: Float, color: Int, now: Double, count: Int) {
        repeat(count) {
            val p = particles[particleCursor]
            particleCursor = (particleCursor + 1) % particles.size
            p.active = true
            p.x = x; p.y = y
            p.vx = (rnd.nextFloat() - 0.5f) * 420f
            p.vy = -(120f + rnd.nextFloat() * 380f)
            p.born = now
            p.color = color
        }
    }

    private fun drawParticles(canvas: Canvas, now: Double) {
        for (p in particles) {
            if (!p.active) continue
            val age = (now - p.born).toFloat()
            if (age < 0f || age > PARTICLE_LIFE) { p.active = false; continue }
            val fade = 1f - age / PARTICLE_LIFE
            val x = p.x + p.vx * age
            val y = p.y + p.vy * age + 900f * age * age
            particlePaint.color = Color.argb(
                (fade * 220).toInt().coerceIn(0, 255),
                Color.red(p.color), Color.green(p.color), Color.blue(p.color)
            )
            canvas.drawCircle(x, y, 7f * fade + 2f, particlePaint)
        }
    }

    private var lastSeenCombo = 0
    private var lastSeenJudgementAt = -1.0

    private fun drawHud(canvas: Canvas, src: RenderSource, w: Float, h: Float, t: Double) {
        hudPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${src.score}", 28f, 70f, hudPaint)
        hudPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("%.1f%%".format(src.accuracy * 100f), w - 28f, 70f, hudPaint)

        val j = src.lastJudgement
        val judgeAge = t - src.lastJudgementAtSec

        // Новая оценка — повод сыпнуть частицами. Отрисовка сама замечает
        // смену события, чтобы логика не знала про эффекты.
        if (j != null && src.lastJudgementAtSec != lastSeenJudgementAt) {
            lastSeenJudgementAt = src.lastJudgementAtSec
            if (j != Judgement.MISS) {
                val lanes = max(1, src.laneCount)
                for (lane in 0 until lanes) {
                    if (t - src.laneFlashAtSec(lane) < 0.05) {
                        val laneW = w / lanes
                        spawnParticles(
                            laneW * (lane + 0.5f), h * 0.84f,
                            laneColors[lane % laneColors.size], t,
                            if (j == Judgement.PERFECT) 14 else 7
                        )
                    }
                }
            }
        }

        if (src.combo > 2) {
            // Комбо подпрыгивает в момент прироста — короткая обратная связь.
            val bump = if (src.combo != lastSeenCombo) { lastSeenCombo = src.combo; 1f } else 0f
            val scale = 1f + 0.12f * bump * (1f - (judgeAge / 0.18).toFloat().coerceIn(0f, 1f))
            comboPaint.textSize = 110f * scale
            comboPaint.color = when {
                src.combo >= 100 -> Color.parseColor("#FFB020")
                src.combo >= 50 -> Color.parseColor("#00E5C0")
                else -> Color.parseColor("#8FA0C4")
            }
            canvas.drawText("${src.combo}", w / 2f, h * 0.34f, comboPaint)
            canvas.drawText("COMBO", w / 2f, h * 0.34f + 34f, comboLabelPaint)
        } else {
            lastSeenCombo = 0
        }

        if (j != null && judgeAge < JUDGE_SHOW_SEC) {
            val fade = 1f - (judgeAge / JUDGE_SHOW_SEC).toFloat()
            val base = when (j) {
                Judgement.PERFECT -> Color.parseColor("#00E5C0")
                Judgement.GOOD -> Color.parseColor("#FFC24A")
                Judgement.EARLY_RELEASE -> Color.parseColor("#FF8A3D")
                Judgement.MISS -> Color.parseColor("#FF4D6D")
            }
            judgePaint.color = Color.argb(
                (255 * fade).toInt().coerceIn(0, 255),
                Color.red(base), Color.green(base), Color.blue(base)
            )
            canvas.drawText(j.title, w / 2f, h * 0.46f - (1f - fade) * 22f, judgePaint)
        }
    }

    private fun drawProgress(canvas: Canvas, src: RenderSource, w: Float, h: Float) {
        val barH = 6f
        canvas.drawRect(0f, h - barH, w, h, progressBgPaint)
        canvas.drawRect(0f, h - barH, w * src.progress(), h, progressPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sink = input ?: return false
        val src = source ?: return false
        val lanes = max(1, src.laneCount)
        val laneW = width.toFloat() / lanes

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val lane = (event.getX(index) / laneW).toInt().coerceIn(0, lanes - 1)
                pointerLane[event.getPointerId(index)] = lane
                sink.onTap(lane, event.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointerLane.remove(id)?.let { sink.onRelease(it, event.eventTime) }
            }
            MotionEvent.ACTION_CANCEL -> {
                // Жест перехвачен системой — считаем все удержания отпущенными,
                // иначе они зависнут активными до конца трека.
                for ((_, lane) in pointerLane) sink.onRelease(lane, event.eventTime)
                pointerLane.clear()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val FLASH_SEC = 0.20
        const val JUDGE_SHOW_SEC = 0.45
        const val PARTICLE_LIFE = 0.55f
        const val MAX_PARTICLES = 120
    }
}
