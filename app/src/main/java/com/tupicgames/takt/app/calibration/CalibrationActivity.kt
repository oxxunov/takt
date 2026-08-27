package com.tupicgames.takt.app.calibration

import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tupicgames.takt.app.R
import com.tupicgames.takt.engine.audio.ClickEngine
import com.tupicgames.takt.engine.data.AppGraph
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Калибровка задержки.
 *
 * Без неё игра нерабочая: выходная задержка на Android гуляет от ~40 до 200+ мс
 * в зависимости от устройства и от того, идёт звук в динамик, по Bluetooth или
 * в проводные наушники. Одна и та же раскладка на двух телефонах без
 * калибровки ощущается совершенно по-разному.
 *
 * Момент реального звучания щелчка берётся из таймштампа AAudio — он уже
 * содержит аппаратную задержку вывода. Остаток расхождения (ввод плюс реакция
 * игрока) и есть искомая поправка.
 */
class CalibrationActivity : AppCompatActivity() {

    private val deltas = ArrayList<Double>()
    private var click: ClickEngine? = null

    private lateinit var instruction: TextView
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        instruction = findViewById(R.id.instruction)
        result = findViewById(R.id.result)

        val prefs = AppGraph.prefs(this)
        result.text = getString(R.string.current_offset_fmt, prefs.offsetMs)

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            deltas.clear()
            prefs.offsetMs = 0
            prefs.calibrated = false
            result.text = getString(R.string.current_offset_fmt, 0)
            instruction.setText(R.string.calib_instruction)
        }
        findViewById<Button>(R.id.doneButton).setOnClickListener { finish() }

        findViewById<View>(R.id.tapArea).setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                v.performClick()
                onTap(e.eventTime)
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        val engine = ClickEngine()
        if (!engine.open()) {
            instruction.setText(R.string.audio_engine_failed)
            return
        }
        engine.startMetronome(PERIOD_SEC)
        click = engine
    }

    override fun onPause() {
        super.onPause()
        click?.stopMetronome()
        click?.close()
        click = null
    }

    private fun onTap(eventTimeMillis: Long) {
        val engine = click ?: return
        val clickNanos = engine.lastClickNanos()
        if (clickNanos == 0L) return

        val ageMs = (SystemClock.uptimeMillis() - eventTimeMillis).coerceAtLeast(0L)
        val tapNanos = System.nanoTime() - ageMs * 1_000_000L

        var delta = (tapNanos - clickNanos) / 1e9
        // Игрок мог попасть ближе к следующему щелчку — сводим расхождение
        // к диапазону в половину периода в обе стороны.
        while (delta > PERIOD_SEC / 2) delta -= PERIOD_SEC.toDouble()
        while (delta < -PERIOD_SEC / 2) delta += PERIOD_SEC.toDouble()
        deltas.add(delta)

        if (deltas.size <= WARMUP) {
            instruction.text = getString(R.string.calib_warmup_fmt, deltas.size, WARMUP)
            return
        }

        // Медиана, а не среднее: один зевок портит среднее целиком.
        val usable = deltas.drop(WARMUP).sorted()
        val median = usable[usable.size / 2]
        val spread = usable.last() - usable.first()

        val prefs = AppGraph.prefs(this)
        prefs.offsetMs = (median * 1000).roundToInt()
        prefs.calibrated = true

        instruction.text = getString(R.string.calib_taps_fmt, usable.size)
        result.text = getString(
            R.string.calib_result_fmt, prefs.offsetMs, (abs(spread) * 1000).roundToInt()
        )
    }

    private companion object {
        const val PERIOD_SEC = 0.8f
        const val WARMUP = 4
    }
}
