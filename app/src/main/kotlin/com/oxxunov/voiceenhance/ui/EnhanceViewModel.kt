package com.oxxunov.voiceenhance.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oxxunov.voiceenhance.audioio.AacEncoder
import com.oxxunov.voiceenhance.audioio.AudioImporter
import com.oxxunov.voiceenhance.audioio.AudioPlayer
import com.oxxunov.voiceenhance.audioio.AudioRecorder
import com.oxxunov.voiceenhance.audioio.Mp3Encoder
import com.oxxunov.voiceenhance.engine.AudioAnalysis
import com.oxxunov.voiceenhance.engine.AudioAnalyzer
import com.oxxunov.voiceenhance.engine.AudioEdits
import com.oxxunov.voiceenhance.engine.AutoEnhancer
import com.oxxunov.voiceenhance.engine.EnhanceSettings
import com.oxxunov.voiceenhance.engine.EnhancementPipeline
import com.oxxunov.voiceenhance.engine.FileStage
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import com.oxxunov.voiceenhance.engine.VoicePresets
import com.oxxunov.voiceenhance.engine.VoiceProfile
import com.oxxunov.voiceenhance.engine.VoiceProfiler
import com.oxxunov.voiceenhance.engine.WavFormat
import com.oxxunov.voiceenhance.engine.WavWriter
import com.oxxunov.voiceenhance.engine.Waveform
import com.oxxunov.voiceenhance.engine.WpeDereverb
import com.oxxunov.voiceenhance.ml.DeepFilterDenoiser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class Busy(val label: String, val progress: Float)

data class RecorderUi(
    val open: Boolean = false,
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val bitDepth: WavFormat = WavFormat.PCM24,
)

enum class ExportKind(val label: String, val mime: String, val ext: String) {
    WAV("WAV", "audio/wav", "wav"),
    M4A("M4A (AAC)", "audio/mp4", "m4a"),
    MP3("MP3", "audio/mpeg", "mp3"),
}

data class UiState(
    val original: PcmFile? = null,
    val originalName: String = "",
    val enhanced: PcmFile? = null,
    val originalAnalysis: AudioAnalysis? = null,
    val enhancedAnalysis: AudioAnalysis? = null,
    val originalPeaks: FloatArray? = null,
    val enhancedPeaks: FloatArray? = null,
    val listenEnhanced: Boolean = false,
    val settings: EnhanceSettings = EnhanceSettings(),
    val busy: Busy? = null,
    val error: String? = null,
    val message: String? = null,
    val recorder: RecorderUi = RecorderUi(),
    val exportKind: ExportKind = ExportKind.WAV,
    val exportFormat: WavFormat = WavFormat.PCM24,
    val exportKbps: Int = 256,
    /** Enhancement Strength, 0..100 %. */
    val strength: Double = 50.0,
    /** Что подобрал ✨ Enhance (пусто — настройки ручные). */
    val autoNotes: List<String> = emptyList(),
    val activePreset: String? = null,
    val userPresets: List<UserPreset> = emptyList(),
    /** Выделенный участок в кадрах [start, end). */
    val selection: Pair<Long, Long>? = null,
    val loop: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/** Состояние проекта для Undo/Redo. Файлы не удаляются, пока на них ссылается история. */
private data class Snapshot(
    val original: PcmFile?,
    val originalName: String,
    val originalAnalysis: AudioAnalysis?,
    val originalPeaks: FloatArray?,
    val enhanced: PcmFile?,
    val enhancedAnalysis: AudioAnalysis?,
    val enhancedPeaks: FloatArray?,
    val settings: EnhanceSettings,
    val autoNotes: List<String>,
)

class EnhanceViewModel(app: Application) : AndroidViewModel(app) {
    private val importer = AudioImporter(app)
    private val denoiser = DeepFilterDenoiser(app)
    private val presetStore = PresetStore(app)
    /** Профиль голоса текущей записи — считается один раз. */
    @Volatile private var profile: Pair<PcmFile, VoiceProfile>? = null
    val recorder = AudioRecorder(app)
    val player = AudioPlayer()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private val workDir: File
        get() = File(getApplication<Application>().cacheDir, "audio").apply { mkdirs() }

    private val lock = Any()
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private val maxHistory = 10

    init {
        // временные файлы прошлых сессий
        workDir.listFiles()?.forEach { it.delete() }
        _state.update { it.copy(userPresets = presetStore.load()) }
    }

    fun updateSettings(transform: (EnhanceSettings) -> EnhanceSettings) {
        _state.update { it.copy(settings = transform(it.settings), autoNotes = emptyList(), activePreset = null) }
    }

    fun setStrength(v: Double) = _state.update { it.copy(strength = v) }

    fun showError(msg: String) = _state.update { it.copy(error = msg) }

    fun dismissMessages() = _state.update { it.copy(error = null, message = null) }

    // ---------- история ----------

    private fun snapshot(s: UiState = _state.value) = Snapshot(
        s.original, s.originalName, s.originalAnalysis, s.originalPeaks,
        s.enhanced, s.enhancedAnalysis, s.enhancedPeaks, s.settings, s.autoNotes,
    )

    private fun pushUndo() = synchronized(lock) {
        undoStack.addLast(snapshot())
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun clearHistory() = synchronized(lock) {
        undoStack.clear()
        redoStack.clear()
    }

    private fun syncHistoryFlags() = synchronized(lock) {
        val u = undoStack.isNotEmpty()
        val r = redoStack.isNotEmpty()
        _state.update { it.copy(canUndo = u, canRedo = r) }
    }

    /** Удаляет файлы, на которые уже не ссылается ни текущее состояние, ни история. */
    private fun cleanup() {
        val keep = HashSet<String>()
        synchronized(lock) {
            (undoStack + redoStack + snapshot()).forEach { sn ->
                sn.original?.let { keep += it.file.absolutePath }
                sn.enhanced?.let { keep += it.file.absolutePath }
            }
        }
        if (recorder.level.value.recording) return
        workDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".f32") && f.absolutePath !in keep) f.delete()
        }
    }

    private fun restore(sn: Snapshot) {
        player.pause()
        player.clearLoop()
        _state.update {
            it.copy(
                original = sn.original, originalName = sn.originalName, originalAnalysis = sn.originalAnalysis,
                originalPeaks = sn.originalPeaks, enhanced = sn.enhanced, enhancedAnalysis = sn.enhancedAnalysis,
                enhancedPeaks = sn.enhancedPeaks, settings = sn.settings, autoNotes = sn.autoNotes,
                listenEnhanced = sn.enhanced != null, selection = null, loop = false, activePreset = null,
            )
        }
        val src = sn.enhanced ?: sn.original
        player.setSource(src, keepPosition = src != null && src.frames > player.position.value)
        syncHistoryFlags()
    }

    fun undo() {
        if (_state.value.busy != null) return
        val prev = synchronized(lock) {
            val p = undoStack.removeLastOrNull() ?: return
            redoStack.addLast(snapshot())
            p
        }
        restore(prev)
    }

    fun redo() {
        if (_state.value.busy != null) return
        val next = synchronized(lock) {
            val n = redoStack.removeLastOrNull() ?: return
            undoStack.addLast(snapshot())
            n
        }
        restore(next)
    }

    // ---------- пресеты ----------

    fun applyPreset(name: String) {
        val s = VoicePresets.all.firstOrNull { it.name == name }?.settings
            ?: _state.value.userPresets.firstOrNull { it.name == name }?.settings
            ?: return
        _state.update { it.copy(settings = s, activePreset = name, autoNotes = emptyList()) }
    }

    fun saveUserPreset(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val list = _state.value.userPresets.filter { it.name != n } + UserPreset(n, _state.value.settings)
        presetStore.save(list)
        _state.update { it.copy(userPresets = list, activePreset = n, message = "Пресет «$n» сохранён") }
    }

    fun deleteUserPreset(name: String) {
        val list = _state.value.userPresets.filter { it.name != name }
        presetStore.save(list)
        _state.update { it.copy(userPresets = list, activePreset = if (it.activePreset == name) null else it.activePreset) }
    }

    // ---------- импорт и запись ----------

    fun importAudio(uri: Uri, name: String) = launchBusy("Импорт") { report ->
        player.pause()
        val out = File(workDir, "original_${System.nanoTime()}.f32")
        val ctx = currentCoroutineContext()
        val pcm = importer.import(uri, out, { report("Импорт", it) }, { !ctx.isActive })
        setOriginal(pcm, name, report)
    }

    private suspend fun setOriginal(pcm: PcmFile, name: String, report: (String, Float) -> Unit) {
        val ctx = currentCoroutineContext()
        val analysis = AudioAnalyzer.analyze(pcm, { report("Анализ", it) }, { !ctx.isActive })
        val peaks = Waveform.peaks(pcm) { !ctx.isActive }
        player.clearLoop()
        player.setSource(pcm, keepPosition = false)
        clearHistory()
        _state.update {
            it.copy(
                original = pcm, originalName = name, originalAnalysis = analysis, originalPeaks = peaks,
                enhanced = null, enhancedAnalysis = null, enhancedPeaks = null, listenEnhanced = false,
                autoNotes = emptyList(), selection = null, loop = false,
            )
        }
        syncHistoryFlags()
        cleanup()
    }

    fun openRecorder() {
        val r = _state.value.recorder
        try {
            recorder.start(r.sampleRate, r.channels)
            _state.update { it.copy(recorder = r.copy(open = true)) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Не удалось открыть микрофон") }
        }
    }

    fun setRecorderFormat(sampleRate: Int, channels: Int, bitDepth: WavFormat) {
        val r = _state.value.recorder.copy(sampleRate = sampleRate, channels = channels, bitDepth = bitDepth)
        _state.update { it.copy(recorder = r, exportFormat = bitDepth) }
        if (r.open && !recorder.level.value.recording) {
            try {
                recorder.start(sampleRate, channels)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Микрофон не поддерживает эти настройки") }
            }
        }
    }

    fun startRecording() {
        player.pause()
        recorder.beginWriting(File(workDir, "rec_${System.nanoTime()}.f32"))
    }

    fun stopRecording() {
        val pcm = recorder.stop()
        _state.update { it.copy(recorder = it.recorder.copy(open = false)) }
        if (pcm == null) return
        if (pcm.frames < pcm.sampleRate / 10) {
            pcm.file.delete()
            _state.update { it.copy(error = "Запись слишком короткая") }
            return
        }
        launchBusy("Анализ") { report -> setOriginal(pcm, "Запись", report) }
    }

    fun closeRecorder() {
        recorder.stop()?.file?.delete()
        _state.update { it.copy(recorder = it.recorder.copy(open = false)) }
    }

    // ---------- ✨ Enhance и обработка ----------

    fun autoEnhance() {
        val s = _state.value
        val input = s.original ?: return
        val analysis = s.originalAnalysis ?: return
        launchBusy("Анализ голоса") { report ->
            val ctx = currentCoroutineContext()
            val cached = profile
            val p = if (cached != null && cached.first.file == input.file) cached.second else {
                VoiceProfiler.profile(input, { report("Анализ голоса", it) }, { !ctx.isActive })
                    .also { profile = input to it }
            }
            val auto = AutoEnhancer.suggest(analysis, p, s.strength / 100.0)
            runProcessing(input, auto.settings, analysis, report, auto.notes)
        }
    }

    fun enhance() {
        val s = _state.value
        val input = s.original ?: return
        launchBusy("Обработка") { report -> runProcessing(input, s.settings, s.originalAnalysis, report, s.autoNotes) }
    }

    private suspend fun runProcessing(
        input: PcmFile,
        settings: EnhanceSettings,
        analysis: AudioAnalysis?,
        report: (String, Float) -> Unit,
        notes: List<String>,
    ) {
        val out = File(workDir, "enhanced_${System.nanoTime()}.f32")
        val ctx = currentCoroutineContext()
        val nr = settings.noiseReduction
        val dr = settings.deReverb
        val pre = buildList<FileStage> {
            if (nr.enabled) add(denoiser.stage(nr.attenLimitDb))
            if (dr.enabled) add(WpeDereverb(dr.strength / 100.0))
        }
        val label = when {
            nr.enabled && dr.enabled -> "Обработка (шумодав + de-reverb + DSP)"
            nr.enabled -> "Обработка (шумодав + DSP)"
            dr.enabled -> "Обработка (de-reverb + DSP)"
            else -> "Обработка"
        }
        val result = EnhancementPipeline.process(
            input, out, workDir, settings, analysis,
            { report(label, it) }, { !ctx.isActive },
            preStages = pre,
        )
        val resAnalysis = AudioAnalyzer.analyze(result, { report("Анализ результата", it) }, { !ctx.isActive })
        val peaks = Waveform.peaks(result) { !ctx.isActive }
        pushUndo()
        _state.update {
            it.copy(
                enhanced = result, enhancedAnalysis = resAnalysis, enhancedPeaks = peaks, listenEnhanced = true,
                settings = settings, autoNotes = notes,
            )
        }
        player.setSource(result, keepPosition = true)
        syncHistoryFlags()
        cleanup()
    }

    fun cancel() {
        job?.cancel()
    }

    // ---------- прослушивание и выделение ----------

    fun listen(enhanced: Boolean) {
        val s = _state.value
        val src = if (enhanced) s.enhanced else s.original
        if (src == null) return
        _state.update { it.copy(listenEnhanced = enhanced) }
        player.setSource(src, keepPosition = true)
    }

    fun togglePlay() {
        if (player.playing.value) player.pause() else player.play()
    }

    fun seek(frame: Long) = player.seek(frame)

    fun setSelection(a: Long, b: Long) {
        val total = _state.value.original?.frames ?: return
        val s = minOf(a, b).coerceIn(0, total)
        val e = maxOf(a, b).coerceIn(0, total)
        val sr = _state.value.original?.sampleRate ?: 48000
        if (e - s < sr / 50) { // < 20 мс — считаем касанием, а не выделением
            clearSelection()
            return
        }
        _state.update { it.copy(selection = s to e) }
        if (_state.value.loop) player.setLoop(s, e)
    }

    fun clearSelection() {
        player.clearLoop()
        _state.update { it.copy(selection = null, loop = false) }
    }

    fun toggleLoop() {
        val sel = _state.value.selection ?: return
        val on = !_state.value.loop
        if (on) player.setLoop(sel.first, sel.second) else player.clearLoop()
        _state.update { it.copy(loop = on) }
    }

    // ---------- правки ----------

    enum class Edit(val label: String) { KEEP("Обрезка"), FADE_IN("Fade in"), FADE_OUT("Fade out") }

    /** Правка применяется и к оригиналу, и к обработке — таймлайны остаются совпадающими для A/B. */
    fun applyEdit(edit: Edit) {
        val s = _state.value
        val sel = s.selection ?: return
        val orig = s.original ?: return
        if (edit == Edit.KEEP && sel.second - sel.first < orig.sampleRate / 10) {
            showError("Выделение слишком короткое для обрезки")
            return
        }
        launchBusy(edit.label) { report ->
            val ctx = currentCoroutineContext()
            player.pause()
            player.clearLoop()
            fun apply(p: PcmFile, tag: String): PcmFile {
                val out = File(workDir, "${tag}_${System.nanoTime()}.f32")
                return when (edit) {
                    Edit.KEEP -> AudioEdits.trim(p, sel.first, sel.second, out)
                    Edit.FADE_IN -> AudioEdits.fade(p, sel.first, sel.second, true, out)
                    Edit.FADE_OUT -> AudioEdits.fade(p, sel.first, sel.second, false, out)
                }
            }
            val newOrig = apply(orig, "original")
            report(edit.label, 0.3f)
            val newEnh = s.enhanced?.let { apply(it, "enhanced") }
            val oa = AudioAnalyzer.analyze(newOrig, { report("Анализ", 0.3f + 0.3f * it) }, { !ctx.isActive })
            val op = Waveform.peaks(newOrig) { !ctx.isActive }
            val ea = newEnh?.let { AudioAnalyzer.analyze(it, { p -> report("Анализ", 0.6f + 0.3f * p) }, { !ctx.isActive }) }
            val ep = newEnh?.let { Waveform.peaks(it) { !ctx.isActive } }
            pushUndo()
            _state.update {
                it.copy(
                    original = newOrig, originalAnalysis = oa, originalPeaks = op,
                    enhanced = newEnh, enhancedAnalysis = ea, enhancedPeaks = ep,
                    selection = if (edit == Edit.KEEP) null else it.selection, loop = false,
                )
            }
            val src = if (_state.value.listenEnhanced && newEnh != null) newEnh else newOrig
            if (edit == Edit.KEEP) player.seek(0)
            player.setSource(src, keepPosition = edit != Edit.KEEP)
            syncHistoryFlags()
            cleanup()
        }
    }

    // ---------- экспорт ----------

    fun setExportKind(k: ExportKind) = _state.update { it.copy(exportKind = k) }
    fun setExportFormat(f: WavFormat) = _state.update { it.copy(exportFormat = f) }
    fun setExportKbps(v: Int) = _state.update { it.copy(exportKbps = v) }

    fun export(uri: Uri) {
        val s = _state.value
        val pcm = s.enhanced ?: return
        val kind = s.exportKind
        launchBusy("Экспорт ${kind.label}") { report ->
            val ctx = currentCoroutineContext()
            val resolver = getApplication<Application>().contentResolver
            val cancelled = { !ctx.isActive }
            val progress: (Float) -> Unit = { p -> report("Экспорт ${kind.label}", p * 0.95f) }
            when (kind) {
                ExportKind.WAV -> {
                    val os = resolver.openOutputStream(uri, "wt") ?: throw IOException("Не удалось создать файл")
                    os.use { WavWriter.write(pcm, it, s.exportFormat, progress, cancelled) }
                }
                ExportKind.M4A, ExportKind.MP3 -> {
                    val tmp = File(workDir, "export_${System.nanoTime()}.${kind.ext}")
                    try {
                        if (kind == ExportKind.M4A) AacEncoder.encode(pcm, tmp, s.exportKbps, progress, cancelled)
                        else Mp3Encoder.encode(pcm, tmp, s.exportKbps, progress, cancelled)
                        val os = resolver.openOutputStream(uri, "wt") ?: throw IOException("Не удалось создать файл")
                        os.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                    } finally {
                        tmp.delete()
                    }
                }
            }
            val q = if (kind == ExportKind.WAV) s.exportFormat.label else "${s.exportKbps} кбит/с"
            _state.update { it.copy(message = "Сохранено: ${kind.label}, $q") }
        }
    }

    // ---------- служебное ----------

    private fun launchBusy(label: String, block: suspend (report: (String, Float) -> Unit) -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(busy = Busy(label, 0f), error = null, message = null) }
            var last = -1f
            val report: (String, Float) -> Unit = { l, p ->
                if (p - last >= 0.005f || p >= 1f || p < last) {
                    last = p
                    _state.update { s -> s.copy(busy = Busy(l, p)) }
                }
            }
            try {
                withContext(Dispatchers.Default) { block(report) }
            } catch (e: CancellationException) {
                _state.update { it.copy(message = "Отменено") }
            } catch (e: ProcessingCancelledException) {
                _state.update { it.copy(message = "Отменено") }
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    override fun onCleared() {
        player.release()
        recorder.stop()
        super.onCleared()
    }
}
