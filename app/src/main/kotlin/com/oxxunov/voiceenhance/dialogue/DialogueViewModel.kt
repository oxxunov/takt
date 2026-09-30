package com.oxxunov.voiceenhance.dialogue

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.oxxunov.voiceenhance.video.VideoInfo
import com.oxxunov.voiceenhance.video.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DialogueUi(
    val uri: Uri? = null,
    val name: String = "",
    val info: VideoInfo? = null,
    val track: Int? = null,
    val probing: Boolean = false,
    val running: Boolean = false,
    val stage: Int = 0,
    val pct: Int = 0,
    val resultPath: String? = null,
    val showResult: Boolean = false,
    val error: String? = null,
    val cancelled: Boolean = false,
    val saved: Boolean = false,
    /** Качество: быстрая (DeepFilterNet3), стандартная или высокое (BandIt v2). */
    val mode: String = DialogueWorker.MODE_FAST,
    val strength: com.oxxunov.voiceenhance.engine.RemovalStrength = com.oxxunov.voiceenhance.engine.RemovalStrength.MEDIUM,
    val banditAvailable: Boolean = false,
    /** Источники, сохранённые после обработки (speech / music / effects или speech / other). */
    val stems: List<String> = emptyList(),
    /** Готовые участки ручной правки. */
    val regions: List<RegionUi> = emptyList(),
    /** Черновик участка: начало/конец (мкс, время видео) и громкости. */
    val draftStartUs: Long? = null,
    val draftEndUs: Long? = null,
    val draftGains: Map<String, Float> = emptyMap(),
)

data class RegionUi(val startUs: Long, val endUs: Long, val gains: Map<String, Float>)

class DialogueViewModel(app: Application) : AndroidViewModel(app) {
    private val wm = WorkManager.getInstance(app)
    private val prefs = app.getSharedPreferences("dialogue", 0)
    private val _state = MutableStateFlow(DialogueUi())
    val state: StateFlow<DialogueUi> = _state.asStateFlow()

    private val live = wm.getWorkInfosForUniqueWorkLiveData(DialogueWorker.UNIQUE)
    private val observer = Observer<List<WorkInfo>> { list -> onWork(list.firstOrNull()) }

    private val banditAvailable = com.oxxunov.voiceenhance.ml.BanditModel(app).isAvailable()

    init {
        val savedMode = prefs.getString("mode", DialogueWorker.MODE_FAST)!!.let { if (it == "bandit") DialogueWorker.MODE_STANDARD else it }
        val savedStrength = runCatching {
            com.oxxunov.voiceenhance.engine.RemovalStrength.valueOf(prefs.getString("strength", "MEDIUM")!!)
        }.getOrDefault(com.oxxunov.voiceenhance.engine.RemovalStrength.MEDIUM)
        _state.update { it.copy(banditAvailable = banditAvailable, mode = savedMode, strength = savedStrength) }
        // восстановление после закрытия приложения
        prefs.getString("uri", null)?.let { u ->
            val name = prefs.getString("name", "") ?: ""
            load(Uri.parse(u), name, prefs.getInt("track", -1).takeIf { it >= 0 })
        }
        val restored = DialogueWorker.regionsFromJson(prefs.getString("regions", null))
            .map { RegionUi(it.startUs, it.endUs, it.gains) }
        _state.update { it.copy(regions = restored) }
        live.observeForever(observer)
    }

    private fun onWork(w: WorkInfo?) {
        if (w == null) return
        when (w.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> _state.update {
                it.copy(
                    running = true,
                    stage = w.progress.getInt(DialogueWorker.KEY_STAGE, it.stage),
                    pct = w.progress.getInt(DialogueWorker.KEY_PCT, it.pct),
                    error = null, cancelled = false,
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                val path = w.outputData.getString(DialogueWorker.KEY_RESULT)
                val exists = path != null && File(path).exists()
                val stems = loadStems()
                _state.update {
                    it.copy(
                        running = false, stage = 4, pct = 100, resultPath = if (exists) path else null,
                        stems = stems, draftGains = if (it.draftGains.keys == stems.toSet()) it.draftGains else defaultGains(stems),
                    )
                }
            }
            WorkInfo.State.FAILED -> {
                val err = w.outputData.getString(DialogueWorker.KEY_ERROR)
                _state.update {
                    if (err == DialogueWorker.ERROR_CANCELLED) it.copy(running = false, cancelled = true, pct = 0)
                    else it.copy(running = false, error = err ?: "Неизвестная ошибка", pct = 0)
                }
            }
            WorkInfo.State.CANCELLED -> _state.update { it.copy(running = false, cancelled = true, pct = 0) }
        }
    }

    private fun loadStems(): List<String> = try {
        val meta = File(DialogueWorker.projectDir(getApplication()), "meta.json")
        val a = org.json.JSONObject(meta.readText()).getJSONArray("stems")
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun defaultGains(stems: List<String>) = stems.associateWith { if (it == "speech") 0f else 1f }

    // ---------- ручная правка участков ----------

    fun markStart(us: Long) = _state.update { it.copy(draftStartUs = us) }
    fun markEnd(us: Long) = _state.update { it.copy(draftEndUs = us) }
    fun setDraftGain(name: String, v: Float) = _state.update { it.copy(draftGains = it.draftGains + (name to v)) }

    enum class RegionPreset { REMOVE_WHISPER, RESTORE, SILENCE }

    fun applyDraftPreset(p: RegionPreset) = _state.update { st ->
        val g = when (p) {
            RegionPreset.RESTORE -> st.stems.associateWith { 1f }
            RegionPreset.SILENCE -> st.stems.associateWith { 0f }
            RegionPreset.REMOVE_WHISPER -> st.stems.associateWith {
                when (it) {
                    "speech", "effects" -> 0f
                    "other" -> 0.3f
                    else -> 1f
                }
            }
        }
        st.copy(draftGains = g)
    }

    fun addRegion() {
        val s = _state.value
        val a = s.draftStartUs ?: return
        val b = s.draftEndUs ?: return
        if (b - a < 50_000) return
        val list = s.regions + RegionUi(minOf(a, b), maxOf(a, b), s.draftGains)
        _state.update { it.copy(regions = list, draftStartUs = null, draftEndUs = null) }
        saveRegions(list)
    }

    fun removeRegion(i: Int) {
        val list = _state.value.regions.filterIndexed { k, _ -> k != i }
        _state.update { it.copy(regions = list) }
        saveRegions(list)
    }

    private fun saveRegions(list: List<RegionUi>) {
        prefs.edit().putString("regions", DialogueWorker.regionsToJson(list.map {
            com.oxxunov.voiceenhance.video.DialogueRemover.RegionSpec(it.startUs, it.endUs, it.gains)
        })).apply()
    }

    /** Пересобирает звук с учётом участков (модель заново не запускается). */
    fun applyRegions() {
        val s = _state.value
        val uri = s.uri ?: return
        val req = OneTimeWorkRequestBuilder<DialogueWorker>()
            .setInputData(
                workDataOf(
                    DialogueWorker.KEY_ACTION to DialogueWorker.ACTION_RENDER,
                    DialogueWorker.KEY_URI to uri.toString(),
                    DialogueWorker.KEY_NAME to s.name,
                    DialogueWorker.KEY_REGIONS to DialogueWorker.regionsToJson(s.regions.map {
                        com.oxxunov.voiceenhance.video.DialogueRemover.RegionSpec(it.startUs, it.endUs, it.gains)
                    }),
                )
            )
            .build()
        _state.update { it.copy(running = true, stage = 2, pct = 0, error = null, cancelled = false, saved = false) }
        wm.enqueueUniqueWork(DialogueWorker.UNIQUE, ExistingWorkPolicy.REPLACE, req)
    }

    fun pick(uri: Uri, name: String) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        wm.pruneWork()
        _state.update { DialogueUi(banditAvailable = banditAvailable, mode = it.mode, strength = it.strength) }
        load(uri, name, null)
    }

    private fun load(uri: Uri, name: String, track: Int?) {
        _state.update { it.copy(uri = uri, name = name, probing = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { VideoProbe.probe(getApplication(), uri) }
                val t = track?.takeIf { tr -> info.audioTracks.any { it.index == tr } } ?: info.audioTracks.firstOrNull()?.index
                _state.update { it.copy(info = info, track = t, probing = false) }
                prefs.edit().putString("uri", uri.toString()).putString("name", name).putInt("track", t ?: -1).apply()
            } catch (e: Exception) {
                _state.update { it.copy(probing = false, error = e.message ?: "Не удалось открыть видео") }
            }
        }
    }

    fun selectTrack(i: Int) {
        _state.update { it.copy(track = i) }
        prefs.edit().putInt("track", i).apply()
    }

    fun start() {
        val s = _state.value
        val uri = s.uri ?: return
        val track = s.track ?: return
        val req = OneTimeWorkRequestBuilder<DialogueWorker>()
            .setInputData(
                workDataOf(
                    DialogueWorker.KEY_URI to uri.toString(),
                    DialogueWorker.KEY_TRACK to track,
                    DialogueWorker.KEY_NAME to s.name,
                    DialogueWorker.KEY_MODE to s.mode,
                    DialogueWorker.KEY_STRENGTH to s.strength.name,
                )
            )
            .build()
        _state.update {
            it.copy(
                running = true, stage = 0, pct = 0, resultPath = null, showResult = false, error = null,
                cancelled = false, saved = false, regions = emptyList(), draftStartUs = null, draftEndUs = null,
            )
        }
        prefs.edit().remove("regions").apply()
        wm.enqueueUniqueWork(DialogueWorker.UNIQUE, ExistingWorkPolicy.REPLACE, req)
    }

    fun setMode(m: String) {
        _state.update { it.copy(mode = m) }
        prefs.edit().putString("mode", m).apply()
    }

    fun setStrength(v: com.oxxunov.voiceenhance.engine.RemovalStrength) {
        _state.update { it.copy(strength = v) }
        prefs.edit().putString("strength", v.name).apply()
    }

    fun cancel() {
        wm.cancelUniqueWork(DialogueWorker.UNIQUE)
    }

    fun showResult(v: Boolean) = _state.update { it.copy(showResult = v) }

    fun dismiss() {
        wm.pruneWork()
        _state.update { it.copy(error = null, cancelled = false, saved = false) }
    }

    /** «Обработать другое видео»: очищаем результат и выбор. */
    fun reset() {
        wm.pruneWork()
        DialogueWorker.resultDir(getApplication()).deleteRecursively()
        val mode = _state.value.mode
        val strength = _state.value.strength
        prefs.edit().clear().putString("mode", mode).putString("strength", strength.name).apply()
        _state.update { DialogueUi(banditAvailable = banditAvailable, mode = mode, strength = strength) }
    }

    fun resultFile(): File? = _state.value.resultPath?.let { File(it) }?.takeIf { it.exists() }

    fun saveTo(target: Uri) {
        val f = resultFile() ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(target, "wt")?.use { o ->
                        f.inputStream().use { it.copyTo(o, 1 shl 16) }
                    } ?: error("Не удалось создать файл")
                }
                _state.update { it.copy(saved = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Не удалось сохранить") }
            }
        }
    }

    fun shareIntent(): Intent? {
        val f = resultFile() ?: return null
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (f.extension == "webm") "video/webm" else "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    override fun onCleared() {
        live.removeObserver(observer)
        super.onCleared()
    }
}
