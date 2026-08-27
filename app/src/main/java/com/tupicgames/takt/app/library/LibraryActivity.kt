package com.tupicgames.takt.app.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tupicgames.takt.app.R
import com.tupicgames.takt.app.calibration.CalibrationActivity
import com.tupicgames.takt.app.diag.DiagnosticsActivity
import com.tupicgames.takt.app.diag.DiagnosticsReport
import com.tupicgames.takt.app.play.GameActivity
import com.tupicgames.takt.core.model.Difficulty
import com.tupicgames.takt.engine.data.AppGraph
import com.tupicgames.takt.engine.data.RecentTrack

/** Выбор трека и параметров. Только UI — вся работа в LibraryViewModel. */
class LibraryActivity : AppCompatActivity() {

    private val vm: LibraryViewModel by viewModels()
    private val prefs by lazy { AppGraph.prefs(this) }
    private val records by lazy { AppGraph.records(this) }
    private val recent by lazy { AppGraph.recentTracks(this) }

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var recordText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playButton: Button
    private lateinit var recentLabel: TextView
    private lateinit var adapter: TrackAdapter

    private var ready: LibraryState.Ready? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> takePermission(uri); vm.select(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)
        recordText = findViewById(R.id.recordText)
        progress = findViewById(R.id.progress)
        playButton = findViewById(R.id.playButton)
        recentLabel = findViewById(R.id.recentLabel)

        setupDifficulty()
        setupSensitivity()
        setupHopMode()
        setupHolds()
        setupTrackOffset()
        setupRecentList()

        findViewById<Button>(R.id.pickButton).setOnClickListener { openPicker() }
        findViewById<Button>(R.id.calibrateButton).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.diagButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        playButton.setOnClickListener { launchGame() }
        playButton.isEnabled = false

        vm.state.observe(this) { render(it) }
        showCrashReportIfAny()
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    // --------------------------------------------------------------- настройки --

    private fun setupDifficulty() {
        val spinner = findViewById<Spinner>(R.id.difficultySpinner)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Difficulty.values().map { it.title }
        )
        spinner.setSelection(prefs.difficulty.ordinal)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.difficulty = Difficulty.values()[pos]
                vm.refreshPreview()
                refreshRecent()
                updateRecordLine()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSensitivity() {
        val bar = findViewById<SeekBar>(R.id.sensitivityBar)
        val label = findViewById<TextView>(R.id.sensitivityLabel)
        bar.max = 100
        bar.progress = prefs.sensitivityPercent - 50
        label.text = getString(R.string.sensitivity_fmt, prefs.sensitivityPercent)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.sensitivityPercent = p + 50
                label.text = getString(R.string.sensitivity_fmt, p + 50)
                if (fromUser) vm.refreshPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupHopMode() {
        val box = findViewById<CheckBox>(R.id.hopModeCheck)
        box.isChecked = prefs.hopMode
        box.setOnCheckedChangeListener { _, checked -> prefs.hopMode = checked }
    }

    private fun setupHolds() {
        val box = findViewById<CheckBox>(R.id.holdsCheck)
        box.isChecked = prefs.holdsEnabled
        box.setOnCheckedChangeListener { _, checked ->
            prefs.holdsEnabled = checked
            vm.refreshPreview()
        }
    }

    /**
     * Ползунок смещения для текущего трека.
     *
     * Общая калибровка снимает задержку устройства, но у каждого файла своя
     * пауза в начале. Профессиональные ритм-игры решают это ровно так же —
     * отдельной подстройкой на песню.
     */
    private fun setupTrackOffset() {
        val bar = findViewById<SeekBar>(R.id.trackOffsetBar)
        val label = findViewById<TextView>(R.id.trackOffsetLabel)
        bar.max = 400            // -200..+200 мс
        bar.progress = 200
        label.text = getString(R.string.track_offset_fmt, 0)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val ms = p - 200
                label.text = getString(R.string.track_offset_fmt, ms)
                if (fromUser) ready?.let { prefs.setTrackOffsetMs(it.info.cacheKey, ms) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun refreshTrackOffset() {
        val bar = findViewById<SeekBar>(R.id.trackOffsetBar)
        val label = findViewById<TextView>(R.id.trackOffsetLabel)
        val ms = ready?.let { prefs.trackOffsetMs(it.info.cacheKey) } ?: 0
        bar.progress = ms + 200
        label.text = getString(R.string.track_offset_fmt, ms)
    }

    // ------------------------------------------------------------ недавние треки --

    private fun setupRecentList() {
        adapter = TrackAdapter(this, records)
        val list = findViewById<ListView>(R.id.recentList)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val track = adapter.getItem(position)
            // Анализ уже в кэше — повторный разбор файла не нужен.
            vm.select(track.uri)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val track = adapter.getItem(position)
            AlertDialog.Builder(this)
                .setTitle(track.title)
                .setMessage(R.string.remove_track_q)
                .setPositiveButton(R.string.remove) { _, _ ->
                    recent.remove(track.cacheKey); refreshRecent()
                }
                .setNegativeButton(R.string.close, null)
                .show()
            true
        }
    }

    private fun refreshRecent() {
        val list = recent.list()
        adapter.submit(list, prefs.difficulty)
        recentLabel.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateRecordLine() {
        val r = ready
        if (r == null) { recordText.text = ""; return }
        val best = records.best(r.info.cacheKey, prefs.difficulty)
        recordText.text = if (best == null) getString(R.string.no_record)
        else getString(R.string.record_fmt, best.grade, best.score, best.accuracy * 100f)
    }

    // ------------------------------------------------------------------- состояние --

    private fun render(state: LibraryState) {
        progress.visibility = if (state is LibraryState.Analyzing) View.VISIBLE else View.GONE
        ready = state as? LibraryState.Ready
        playButton.isEnabled = ready != null

        when (state) {
            is LibraryState.Idle ->
                statusText.setText(
                    if (prefs.calibrated) R.string.pick_to_start else R.string.hint_calibrate_first
                )
            is LibraryState.Analyzing -> {
                statusText.text = getString(R.string.analyzing, state.title)
                infoText.text = ""; recordText.text = ""
            }
            is LibraryState.Ready -> {
                statusText.text = getString(
                    if (state.fromCache) R.string.loaded_from_cache else R.string.ready,
                    state.info.title
                )
                val a = state.analysis
                val bm = state.preview
                infoText.text = getString(
                    R.string.info_fmt,
                    a.bpm,
                    (a.durationSec / 60f).toInt(), (a.durationSec % 60f).toInt(),
                    bm.notes.size, bm.notesPerSecond,
                    getString(if (bm.quantized) R.string.grid_on else R.string.grid_off)
                )
                updateRecordLine()
                refreshTrackOffset()
                recent.add(
                    RecentTrack(
                        uri = state.info.uri, title = state.info.title,
                        cacheKey = state.info.cacheKey, bpm = a.bpm,
                        durationSec = a.durationSec, playedAt = System.currentTimeMillis()
                    )
                )
                refreshRecent()
            }
            LibraryState.NoBeats -> {
                statusText.setText(R.string.no_beats_found); infoText.text = ""; recordText.text = ""
            }
            is LibraryState.Error -> {
                statusText.text = getString(R.string.error_fmt, state.message)
                infoText.text = ""; recordText.text = ""
            }
        }
    }

    // ----------------------------------------------------------------- действия --

    private fun takePermission(uri: Uri) {
        // Без стойкого разрешения ссылка на файл перестанет работать
        // после перезапуска, и список недавних окажется бесполезным.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun openPicker() {
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun launchGame() {
        val r = ready ?: return
        startActivity(GameActivity.intent(this, r.info.uri, r.info.cacheKey, r.info.title))
    }

    /**
     * Предлагает открыть отчёт, если прошлый запуск оборвался.
     *
     * Сам отчёт живёт на отдельном экране и никуда не девается: диалог,
     * закрытый не глядя, раньше уносил с собой единственный след сбоя.
     */
    private fun showCrashReportIfAny() {
        if (!DiagnosticsReport.hasReport(this)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_found)
            .setPositiveButton(R.string.open) { _, _ ->
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
