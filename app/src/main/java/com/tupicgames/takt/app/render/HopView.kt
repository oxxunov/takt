package com.tupicgames.takt.app.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.tupicgames.takt.app.play.HopRenderSource
import com.tupicgames.takt.app.play.HopInputSink
import com.tupicgames.takt.core.model.Judgement
import kotlin.math.max

/**
 * Режим с прыгающим шариком.
 *
 * Дорога рисуется в перспективе: чем дальше плитка, тем она меньше и ближе
 * к горизонту. Настоящего трёхмерного движка тут не нужно — хватает одного
 * деления на глубину, зато нет ни загрузки моделей, ни лишних зависимостей.
 *
 * Как и в основном режиме, здесь только отрисовка и ввод: состояние берётся
 * из HopRenderSource, движение пальца уходит в HopInputSink.
 */
class HopView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private var source: HopRenderSource? = null
    private var input: HopInputSink? = null

    /** Сколько секунд трека видно вперёд. */
    var lookaheadSec: Float = 2.2f

    private var thread: Thread? = null
    @Volatile private var rendering = false

    private val skyPaint = Paint()
    private val groundPaint = Paint().apply { color = Color.parseColor("#071019") }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tileEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ballGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 44f; isFakeBoldText = true
    }
    private val comboPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 96f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        color = Color.WHITE
    }
    private val judgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 60f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val progressPaint = Paint().apply { color = Color.parseColor("#3D7BFF") }
    private val progressBgPaint = Paint().apply { color = Color.parseColor("#121A26") }

    private val tilePath = Path()
    private var skyShader: Shader? = null
    private var shaderForHeight = -1f

    private val tileColor = Color.parseColor("#2ED9C3")
    private val tileSideColor = Color.parseColor("#15806F")

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun attach(source: HopRenderSource, input: HopInputSink) {
        this.source = source
        this.input = input
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        rendering = true
        thread = Thread(this, "hop-render").apply { start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        rendering = false
        thread?.join(800)
        thread = null
    }

    override fun run() {
        while (rendering) {
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

    // ------------------------------------------------------------ перспектива --

    /**
     * Масштаб объекта на глубине z.
     *
     * Одно деление вместо матриц: при z = 0 масштаб равен 1, дальше плавно
     * убывает. Этого достаточно для дороги, уходящей к горизонту.
     */
    private fun scaleAt(z: Float): Float = NEAR / (NEAR + z.coerceAtLeast(0f))

    private fun screenY(z: Float, h: Float): Float {
        val horizon = h * HORIZON
        val ground = h * GROUND
        return horizon + (ground - horizon) * scaleAt(z)
    }

    private fun screenX(roadX: Float, ballX: Float, z: Float, w: Float): Float {
        // Экран центрирован по шарику: так его видно, куда бы он ни уехал.
        val offset = roadX - ballX
        return w / 2f + offset * scaleAt(z) * w * ROAD_HALF_WIDTH
    }

    // -------------------------------------------------------------- отрисовка --

    private fun renderFrame(canvas: Canvas) {
        val src = source ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        ensureShaders(h)

        skyPaint.shader = skyShader
        canvas.drawRect(0f, 0f, w, h * HORIZON, skyPaint)
        canvas.drawRect(0f, h * HORIZON, w, h, groundPaint)

        val t = src.songTimeSec()
        val ballX = src.ballX()
        if (src.fell()) {
            // После падения дорогу больше не рисуем — партия окончена.
            drawHud(canvas, src, t, w, h)
            return
        }

        drawTiles(canvas, src, t, ballX, w, h)
        drawBall(canvas, src, t, w, h)
        drawHud(canvas, src, t, w, h)

        val barH = 6f
        canvas.drawRect(0f, h - barH, w, h, progressBgPaint)
        canvas.drawRect(0f, h - barH, w * src.progress(), h, progressPaint)
    }

    /**
     * Плитки — отдельные четырёхугольники с пропастью между ними.
     *
     * Сплошная лента читалась как дорога, по которой можно ехать. Разрыв
     * между плитками меняет само ощущение: видно, что под ногами пустота
     * и промахнуться реально.
     *
     * Ширина пропасти задана долей от интервала прыжка, а не в пикселях:
     * так она остаётся одинаковой на любом темпе.
     */
    private fun drawTiles(
        canvas: Canvas, src: HopRenderSource,
        t: Double, ballX: Float, w: Float, h: Float
    ) {
        val tiles = src.tiles
        if (tiles.isEmpty()) return

        val from = max(0, src.nextIndex() - 1)
        var last = src.nextIndex()
        while (last < tiles.size && tiles[last].timeSec - t < lookaheadSec) last++

        // Дальние рисуем первыми, чтобы ближние их перекрывали.
        for (i in last - 1 downTo from) {
            val tile = tiles[i]
            val dt = (tile.timeSec - t).toFloat()
            if (dt < -0.35f) continue

            // Половина протяжённости плитки во времени. Берём от интервала
            // до соседней, поэтому на быстром треке плитки короче, а доля
            // пропасти остаётся той же.
            val interval = when {
                i + 1 < tiles.size -> tiles[i + 1].timeSec - tile.timeSec
                i > 0 -> tile.timeSec - tiles[i - 1].timeSec
                else -> 0.4f
            }
            val half = interval * TILE_SPAN_FRACTION / 2f

            val dtFar = dt + half
            val dtNear = dt - half
            if (dtFar < -0.3f) continue

            val zFar = max(0f, dtFar) * DEPTH_PER_SEC
            val zNear = max(0f, dtNear) * DEPTH_PER_SEC

            val yFar = screenY(zFar, h)
            val yNear = screenY(zNear, h)
            if (yNear <= yFar) continue

            val sFar = scaleAt(zFar)
            val sNear = scaleAt(zNear)
            val xFar = screenX(tile.x, ballX, zFar, w)
            val xNear = screenX(tile.x, ballX, zNear, w)

            val halfFar = w * TILE_HALF_WIDTH * sFar
            val halfNear = w * TILE_HALF_WIDTH * sNear

            val fade = (1f - dt / lookaheadSec).coerceIn(0f, 1f)

            // Боковая грань под ближним краем — от неё плитка выглядит
            // кубом, а пропасть между плитками становится очевидной.
            val sideHeight = h * TILE_SIDE_HEIGHT * sNear
            tilePath.reset()
            tilePath.moveTo(xNear - halfNear, yNear)
            tilePath.lineTo(xNear + halfNear, yNear)
            tilePath.lineTo(xNear + halfNear, yNear + sideHeight)
            tilePath.lineTo(xNear - halfNear, yNear + sideHeight)
            tilePath.close()
            sidePaint.color = tileSideColor
            sidePaint.alpha = (70 + 120 * fade).toInt().coerceIn(0, 255)
            canvas.drawPath(tilePath, sidePaint)

            // Верхняя грань.
            tilePath.reset()
            tilePath.moveTo(xFar - halfFar, yFar)
            tilePath.lineTo(xFar + halfFar, yFar)
            tilePath.lineTo(xNear + halfNear, yNear)
            tilePath.lineTo(xNear - halfNear, yNear)
            tilePath.close()

            tilePaint.color = tileColor
            tilePaint.alpha = (110 + 145 * fade).toInt().coerceIn(0, 255)
            canvas.drawPath(tilePath, tilePaint)

            // Светящаяся кромка по контуру верхней грани.
            tileEdgePaint.color = Color.WHITE
            tileEdgePaint.alpha = (70 + 165 * fade).toInt().coerceIn(0, 255)
            tileEdgePaint.strokeWidth = max(1.5f, 5f * sNear)
            canvas.drawPath(tilePath, tileEdgePaint)
        }
    }

    private fun drawBall(canvas: Canvas, src: HopRenderSource, t: Double, w: Float, h: Float) {
        val z = 0f
        val groundY = screenY(z, h)
        val radius = w * BALL_RADIUS

        // Тень на дороге показывает, куда шарик приземлится.
        val hop = src.hopHeight(t)
        val shadowScale = 1f - 0.35f * hop
        canvas.drawOval(
            w / 2f - radius * shadowScale, groundY - radius * 0.28f * shadowScale,
            w / 2f + radius * shadowScale, groundY + radius * 0.28f * shadowScale,
            shadowPaint
        )

        val cy = groundY - radius - h * HOP_HEIGHT * hop
        ballGlowPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawCircle(w / 2f, cy, radius * 1.5f, ballGlowPaint)
        canvas.drawCircle(w / 2f, cy, radius, ballPaint)
    }

    private fun drawHud(canvas: Canvas, src: HopRenderSource, t: Double, w: Float, h: Float) {
        hudPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${src.score()}", 28f, 70f, hudPaint)
        hudPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("%.1f%%".format(src.accuracy() * 100f), w - 28f, 70f, hudPaint)

        if (src.combo() > 2) {
            canvas.drawText("${src.combo()}", w / 2f, h * 0.14f, comboPaint)
        }

        val j = src.lastJudgement()
        val age = t - src.lastJudgementAtSec()
        if (j != null && age < 0.5) {
            val fade = 1f - (age / 0.5).toFloat()
            val base = if (j == Judgement.MISS) Color.parseColor("#FF4D6D")
            else Color.parseColor("#8FF0E4")
            judgePaint.color = Color.argb(
                (255 * fade).toInt().coerceIn(0, 255),
                Color.red(base), Color.green(base), Color.blue(base)
            )
            val text = if (j == Judgement.MISS) "МИМО!" else "ОТЛИЧНО"
            canvas.drawText(text, w / 2f, h * 0.24f, judgePaint)
        }
    }

    private fun ensureShaders(h: Float) {
        if (shaderForHeight == h) return
        shaderForHeight = h
        skyShader = LinearGradient(
            0f, 0f, 0f, h * HORIZON,
            Color.parseColor("#0B1A2A"), Color.parseColor("#123040"),
            Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------- ввод --

    private var dragPointerId = -1
    private var dragStartX = 0f
    private var dragStartBallX = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sink = input ?: return false
        val src = source ?: return false
        val w = width.toFloat()
        if (w <= 0f) return true

        // Ведём шарик относительным сдвигом пальца, а не абсолютной позицией:
        // иначе при первом касании шарик прыгает под палец через всю дорогу.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragPointerId = event.getPointerId(0)
                dragStartX = event.x
                dragStartBallX = src.ballX()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragPointerId < 0) return true
                val index = event.findPointerIndex(dragPointerId)
                if (index < 0) return true
                val dx = (event.getX(index) - dragStartX) / (w * ROAD_HALF_WIDTH)
                sink.moveTo(dragStartBallX + dx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragPointerId = -1
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val HORIZON = 0.34f          // доля высоты экрана до линии горизонта
        const val GROUND = 0.80f           // где стоит шарик
        const val NEAR = 3.2f              // чем меньше, тем сильнее перспектива
        const val DEPTH_PER_SEC = 6.0f     // глубина на секунду трека
        const val ROAD_HALF_WIDTH = 0.34f
        const val TILE_HALF_WIDTH = 0.15f
        /** Какую долю интервала между прыжками занимает сама плитка. */
        const val TILE_SPAN_FRACTION = 0.60f
        const val TILE_SIDE_HEIGHT = 0.055f
        const val BALL_RADIUS = 0.065f
        const val HOP_HEIGHT = 0.16f
    }
}
