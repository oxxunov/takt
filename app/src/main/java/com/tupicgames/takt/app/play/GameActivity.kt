package com.tupicgames.takt.app.play

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tupicgames.takt.app.R
import com.tupicgames.takt.app.diag.Breadcrumbs
import com.tupicgames.takt.app.diag.ErrorDialog
import com.tupicgames.takt.app.render.GameView
import com.tupicgames.takt.app.render.HopView
import com.tupicgames.takt.core.generator.BeatmapGenerator
import com.tupicgames.takt.core.generator.HopRoadGenerator
import com.tupicgames.takt.core.model.Analysis
import com.tupicgames.takt.core.model.Beatmap
import com.tupicgames.takt.core.play.GameEngine
import com.tupicgames.takt.core.play.HopEngine
import com.tupicgames.takt.core.play.Grade
import com.tupicgames.takt.core.play.Result
import com.tupicgames.takt.engine.audio.ClickEngine
import com.tupicgames.takt.engine.audio.GameClock
import com.tupicgames.takt.engine.audio.PcmDecoder
import com.tupicgames.takt.engine.audio.PcmPlayer
import com.tupicgames.takt.engine.audio.SystemClock2
import com.tupicgames.takt.engine.data.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран партии.
 *
 * Главный принцип: экран обязан открыться. Всё, что может отказать —
 * декодирование, вывод звука, низколатентные клики — либо уводится
 * в фоновую загрузку с индикатором, либо имеет запасной путь.
 * Молчаливого закрытия быть не должно ни при каком отказе.
 */
class GameActivity : AppCompatActivity() {

    private var player: PcmPlayer? = null
    private var click: ClickEngine? = null
    private var session: GameSession? = null
    private var hopSession: HopSession? = null
    private var resultShown = false
    private var hopMode = false

    private lateinit var gameView: GameView
    private lateinit var hopView: HopView
    private lateinit var loadingBox: View
    private lateinit var loadingText: TextView
    private lateinit var loadingBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_game)

        gameView = findViewById(R.id.gameView)
        hopView = findViewById(R.id.hopView)
        loadingBox = findViewById(R.id.loadingBox)
        loadingText = findViewById(R.id.loadingText)
        loadingBar = findViewById(R.id.loadingBar)

        Breadcrumbs.begin(this, "Запуск уровня")

        val uri = intent.data
        val key = intent.getStringExtra(EXTRA_CACHE_KEY)
        if (uri == null || key == null) { bail(getString(R.string.error_no_track)); return }

        val prefs = AppGraph.prefs(this)
        hopMode = prefs.hopMode
        // Показываем только нужный вид: у каждого свой поток отрисовки,
        // и держать оба работающими незачем.
        gameView.visibility = if (hopMode) View.GONE else View.VISIBLE
        hopView.visibility = if (hopMode) View.VISIBLE else View.GONE
        Breadcrumbs.step("режим: ${if (hopMode) "шарик" else "плитки"}")

        val analysis = AppGraph.analysisCache(this).load(key)
        if (analysis == null) { bail(getString(R.string.error_cache_lost)); return }
        Breadcrumbs.step("анализ из кэша: ${analysis.onsets.size} ударов")

        // Режим с шариком строит дорогу по ДОЛЯМ, обычный — раскладку по нотам.
        val road = if (hopMode) HopRoadGenerator.generate(analysis) else null
        val beatmap = if (hopMode) null else BeatmapGenerator.generate(
            analysis, prefs.difficulty, LANES,
            prefs.sensitivityPercent / 100f, prefs.holdsEnabled
        )

        if (hopMode) {
            Breadcrumbs.step("дорога: ${road!!.tiles.size} плиток, " +
                "${"%.1f".format(road.hopsPerSecond)} прыжков/сек")
            if (road.tiles.isEmpty()) { bail(getString(R.string.no_beats_found)); return }
        } else {
            Breadcrumbs.step("раскладка: ${beatmap!!.notes.size} нот")
            if (beatmap.notes.isEmpty()) { bail(getString(R.string.no_beats_found)); return }
        }

        if (hopMode) hopView.lookaheadSec = (prefs.approachMs / 1000f) * 2f
        else gameView.approachSec = prefs.approachMs / 1000f

        // Декодирование целиком — единственная долгая операция. Она уходит
        // в фон под индикатор, а не блокирует открытие экрана.
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    PcmDecoder.decode(this@GameActivity, uri) { p ->
                        runOnUiThread { loadingBar.progress = (p * 100).toInt() }
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch

            decoded.onFailure { e ->
                Breadcrumbs.step("декодирование не удалось")
                ErrorDialog.show(this@GameActivity, getString(R.string.error_setup_title), e) { finish() }
                return@launch
            }

            val pcm = decoded.getOrNull() ?: return@launch
            Breadcrumbs.step("декодировано: ${"%.1f".format(pcm.durationSec)} с")
            // Общая калибровка плюс подстройка под этот конкретный файл.
            val offsetSec = (prefs.offsetMs + prefs.trackOffsetMs(key)) / 1000f
            begin(beatmap, road, pcm, key, offsetSec, analysis)
        }
    }

    private fun begin(
        beatmap: Beatmap?,
        road: com.tupicgames.takt.core.generator.HopRoad?,
        pcm: PcmDecoder.Pcm,
        cacheKey: String,
        offsetSec: Float,
        analysis: Analysis
    ) {
        // Если вывод звука недоступен, уровень всё равно играется —
        // по системным часам и без музыки. Экран, который не открылся,
        // хуже уровня без звука.
        val pcmPlayer = PcmPlayer(pcm)
        val audioOk = runCatching { pcmPlayer.open() }.getOrDefault(false)
        Breadcrumbs.step(if (audioOk) "аудиовыход открыт" else "аудиовыход недоступен, играем молча")

        val clock: GameClock
        var silent: SystemClock2? = null
        if (audioOk) {
            player = pcmPlayer
            clock = pcmPlayer
        } else {
            runCatching { pcmPlayer.release() }
            silent = SystemClock2(pcm.durationSec)
            clock = silent
        }

        val clickEngine = ClickEngine().also { click = it }
        val finish: (Result) -> Unit = { r -> runOnUiThread { showResults(r, cacheKey) } }

        if (hopMode && road != null) {
            val hop = HopSession(
                engine = HopEngine(road),
                clock = clock,
                click = clickEngine,
                calibrationOffsetSec = offsetSec,
                onFinished = finish
            )
            hopSession = hop
            hopView.attach(hop, hop)
        } else if (beatmap != null) {
            val s = GameSession(
                engine = GameEngine(beatmap),
                clock = clock,
                click = clickEngine,
                calibrationOffsetSec = offsetSec,
                envelope = analysis.envelope,
                envelopeHz = analysis.envelopeHz,
                onFinished = finish
            )
            session = s
            gameView.attach(s, s)
        }

        loadingBox.visibility = View.GONE
        Breadcrumbs.step("сессия собрана")

        val view: View = if (hopMode) hopView else gameView
        view.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            try {
                if (audioOk) player?.start() else silent?.start()
                Breadcrumbs.step("воспроизведение запущено")
                // Низколатентные клики — нативный слой. Открываем ПОСЛЕ
                // появления экрана и в стороне: их сбой не должен утащить
                // за собой запуск уровня.
                Thread {
                    Breadcrumbs.step("открываю AAudio")
                    runCatching { clickEngine.open() }
                    Breadcrumbs.step("AAudio отработал")
                }.start()
                if (hopMode) hopSession?.start() else session?.start()
                Breadcrumbs.step("цикл запущен — партия идёт")
                // Отметку "завершено" ставим только при штатном выходе:
                // иначе падение во время игры не отличить от успеха.
            } catch (e: Throwable) {
                ErrorDialog.show(this, getString(R.string.error_setup_title), e) { finish() }
            }
        }, LEAD_IN_MS)
    }

    /** Диалог, а не тост: тост при мгновенном закрытии экрана легко не заметить. */
    private fun bail(message: String) {
        loadingBox.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.error_setup_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.close) { _, _ -> finish() }
            .show()
    }

    private fun showResults(result: Result, cacheKey: String) {
        if (resultShown || isFinishing || isDestroyed) return
        player?.failure?.let {
            resultShown = true
            ErrorDialog.show(this, getString(R.string.error_playback_title), it) { finish() }
            return
        }
        resultShown = true

        val prefs = AppGraph.prefs(this)
        val fell = hopSession?.let { hopMode && it.fell() } ?: false
        val grade = Grade.of(result.accuracy, result.miss, result.total)
        val isRecord = AppGraph.records(this).submit(
            cacheKey, prefs.difficulty, result.score, result.accuracy, result.maxCombo, grade.title
        )

        val holds = if (result.holdsCompleted + result.holdsBroken > 0)
            "\n" + getString(R.string.holds_fmt, result.holdsCompleted, result.holdsBroken)
        else ""

        val body = getString(
            R.string.results_body_fmt,
            grade.title, result.score, result.accuracy * 100f, result.maxCombo,
            result.perfect, result.good, result.miss
        ) + holds + if (isRecord) "\n\n" + getString(R.string.new_record) else ""

        AlertDialog.Builder(this)
            .setTitle(
                if (fell) getString(R.string.fell_title)
                else intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
            )
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.close) { _, _ -> finish() }
            .show()
    }

    override fun onPause() {
        super.onPause()
        // Партию нельзя продолжить с середины: ноты привязаны к абсолютному
        // времени трека. Раньше цикл останавливался и не запускался обратно —
        // экран навсегда замирал с висящими плитками.
        session?.stop()
        hopSession?.stop()
        player?.pause()
        if (!isFinishing && !resultShown) {
            resultShown = true
            AlertDialog.Builder(this)
                .setTitle(R.string.paused_title)
                .setMessage(R.string.paused_body)
                .setCancelable(false)
                .setPositiveButton(R.string.close) { _, _ -> finish() }
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Breadcrumbs.end()
        // Порядок важен: сначала цикл, потом источники звука.
        session?.stop(); session = null
        hopSession?.stop(); hopSession = null
        player?.release(); player = null
        click?.close(); click = null
    }

    companion object {
        private const val EXTRA_CACHE_KEY = "cache_key"
        private const val EXTRA_TITLE = "title"
        private const val LEAD_IN_MS = 2000L
        private const val LANES = 4

        fun intent(context: Context, uri: Uri, cacheKey: String, title: String) =
            Intent(context, GameActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_CACHE_KEY, cacheKey)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
