package com.oxxunov.voiceenhance.mute

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oxxunov.voiceenhance.audioio.AudioImporter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import com.oxxunov.voiceenhance.engine.Waveform
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
import java.util.UUID

data class MuteUi(
    val projects: List<MuteProject> = emptyList(),
    /** Открытый проект; null — показывается список проектов. */
    val project: MuteProject? = null,
    /** Огибающая звука: пары (min, max) на каждые 10 мс. */
    val peaks: FloatArray? = null,
    /** Подготовка волны: текст и прогресс 0..1. */
    val loading: Pair<String, Float>? = null,
    /** Нажато «Начало», ждём «Конец». */
    val pendingStartMs: Long? = null,
    /** Выделение пальцем на волне, ещё не добавленное в участки. */
    val selection: Pair<Long, Long>? = null,
    val selectedId: Long? = null,
    val error: String? = null,
    /** Последняя обработка (результат), если есть. */
    val render: RenderInfo? = null,
    /** Подпись текущих участков — сравнивается с [RenderInfo.signature]. */
    val signature: String = "",
    /** Идёт обработка или сохранение: подпись шага и прогресс 0..1. */
    val busy: Pair<String, Float>? = null,
    /** В плеере — результат (иначе оригинал). */
    val showResult: Boolean = false,
    val modelAvailable: Boolean = false,
    val message: String? = null,
) {
    val resultUpToDate: Boolean get() = render != null && render.signature == signature
}

class MuteViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** Участок короче этого не добавляется — скорее всего, случайное касание. */
        const val MIN_REGION_MS = 150L
        const val BIN_MS = 10
    }

    private val store = MuteStore(app)
    private val renderer = MuteRenderer(app, store)
    private val modelStamp = renderer.modelStamp()
    private val _state = MutableStateFlow(MuteUi(modelAvailable = renderer.modelAvailable()))
    val state: StateFlow<MuteUi> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var workJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(projects = list) }
        }
    }

    // ---------- проекты ----------

    fun create(uri: Uri, name: String) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // некоторые проводники не дают постоянный доступ — тогда видео откроется, пока жив выбор
        }
        val now = System.currentTimeMillis()
        val p = MuteProject(
            id = UUID.randomUUID().toString(),
            name = name.substringBeforeLast('.').ifBlank { "Видео" },
            uri = uri.toString(),
            durationMs = 0L,
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch(Dispatchers.IO) { store.save(p) }
        openLoaded(p)
    }

    fun open(id: String) {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { store.load(id) }
            if (p == null) {
                _state.update { it.copy(error = "Проект не найден") }
                refresh()
            } else openLoaded(p)
        }
    }

    private fun openLoaded(p: MuteProject) {
        _state.update {
            it.copy(
                project = p, peaks = null, pendingStartMs = null, selection = null, selectedId = null, error = null,
                render = null, signature = MuteRenderer.signature(p, modelStamp), showResult = false, message = null,
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { renderer.loadInfo(p.id) }
            _state.update { if (it.project?.id == p.id) it.copy(render = info) else it }
            val cached = withContext(Dispatchers.IO) { store.loadPeaks(p.id) }
            if (cached != null) {
                _state.update { it.copy(peaks = cached) }
                return@launch
            }
            _state.update { it.copy(loading = "Подготовка волны звука" to 0f) }
            try {
                val peaks = withContext(Dispatchers.Default) { buildPeaks(p) }
                withContext(Dispatchers.IO) { store.savePeaks(p.id, peaks) }
                _state.update { if (it.project?.id == p.id) it.copy(peaks = peaks) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessingCancelledException) {
                // закрыли проект во время подготовки
            } catch (e: Throwable) {
                _state.update { it.copy(error = "Не удалось прочитать звук видео: ${e.message ?: e}") }
            } finally {
                _state.update { it.copy(loading = null) }
            }
        }
    }

    /** Декодирует звук видео во временный файл и строит огибающую по 10 мс. Файл звука сразу удаляется. */
    private suspend fun buildPeaks(p: MuteProject): FloatArray {
        val ctx = currentCoroutineContext()
        val app = getApplication<Application>()
        val tmp = File(app.cacheDir, "mute_peaks_${System.nanoTime()}.f32")
        try {
            val pcm = AudioImporter(app).import(
                Uri.parse(p.uri), tmp,
                { f -> _state.update { it.copy(loading = "Подготовка волны звука" to f * 0.9f) } },
                { !ctx.isActive },
            )
            val spb = (pcm.sampleRate * BIN_MS / 1000).coerceAtLeast(1)
            val peaks = Waveform.peaks(pcm, spb) { !ctx.isActive }
            val durMs = pcm.frames * 1000L / pcm.sampleRate
            if (p.durationMs <= 0L) update { it.copy(durationMs = durMs) }
            return peaks
        } finally {
            tmp.delete()
        }
    }

    fun close(positionMs: Long? = null) {
        loadJob?.cancel()
        workJob?.cancel()
        positionMs?.let { savePosition(it) }
        val id = _state.value.project?.id
        _state.update {
            it.copy(
                project = null, peaks = null, loading = null, pendingStartMs = null, selection = null, selectedId = null,
                render = null, busy = null, showResult = false, message = null,
            )
        }
        // декодированный звук видео занимает сотни МБ — при выходе из проекта освобождаем
        if (id != null) viewModelScope.launch(Dispatchers.IO) { renderer.dropCache(id) }
        refresh()
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id); renderer.dropCache(id) }
            refresh()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---------- удаление голоса и сохранение ----------

    /** Долгая работа в фоне с прогрессом; одновременно — только одна. */
    private fun work(label: String, onDone: (MuteProject) -> Unit = {}, block: suspend (MuteProject, (String, Float) -> Unit, () -> Boolean) -> Unit) {
        val p = _state.value.project ?: return
        if (workJob?.isActive == true) return
        _state.update { it.copy(busy = label to 0f, error = null, message = null) }
        workJob = viewModelScope.launch {
            val ctx = currentCoroutineContext()
            try {
                withContext(Dispatchers.Default) {
                    block(p, { l, f -> _state.update { s -> if (s.busy != null) s.copy(busy = l to f.coerceIn(0f, 1f)) else s } }) { !ctx.isActive }
                }
                onDone(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessingCancelledException) {
                // отменено
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(error = "Не хватило памяти телефона. Закройте другие приложения и попробуйте снова.") }
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun cancelWork() {
        workJob?.cancel()
        _state.update { it.copy(busy = null) }
    }

    /** «Удалить голос»: обрабатываются только новые и изменённые участки. */
    fun process() {
        var info: RenderInfo? = null
        work("Подготовка", onDone = { p ->
            _state.update {
                if (it.project?.id == p.id) it.copy(render = info, showResult = true, message = "Готово: голос удалён в ${p.regions.size} участк.")
                else it
            }
        }) { p, progress, cancelled ->
            info = renderer.render(p, progress, cancelled)
        }
    }

    fun exportVideo(target: Uri) {
        val info = _state.value.render ?: return
        work("Сохранение видео", onDone = { _state.update { it.copy(message = "Видео сохранено") } }) { p, progress, cancelled ->
            renderer.exportVideo(p, info, target, { progress("Сохранение видео", it) }, cancelled)
        }
    }

    fun exportWav(target: Uri) {
        work("Сохранение WAV", onDone = { _state.update { it.copy(message = "Звук сохранён (WAV)") } }) { p, progress, cancelled ->
            renderer.exportWav(p, target, { progress("Сохранение WAV", it) }, cancelled)
        }
    }

    fun setShowResult(on: Boolean) = _state.update { it.copy(showResult = on && it.render != null) }

    fun resultFile(): File? {
        val s = _state.value
        val p = s.project ?: return null
        val info = s.render ?: return null
        return renderer.resultFile(p.id, info)
    }

    /** Ползунок «Оставить голос», 0..0.3. */
    fun setKeep(id: Long, value: Float) = update { p ->
        p.copy(regions = p.regions.map { if (it.id == id) it.copy(keepVoice = value.coerceIn(0f, 0.3f)) else it })
    }

    /** Длительность из плеера, если волна ещё не готова. */
    fun setDuration(ms: Long) {
        val p = _state.value.project ?: return
        if (ms > 0 && p.durationMs <= 0L) update { it.copy(durationMs = ms) }
    }

    // ---------- изменения проекта: каждое сразу сохраняется ----------

    private fun update(transform: (MuteProject) -> MuteProject) {
        var saved: MuteProject? = null
        _state.update { s ->
            val p = s.project ?: return@update s
            val np = transform(p).let { it.copy(regions = it.regions.sortedBy { r -> r.startMs }, updatedAt = System.currentTimeMillis()) }
            saved = np
            s.copy(project = np, signature = MuteRenderer.signature(np, modelStamp))
        }
        saved?.let { p -> viewModelScope.launch(Dispatchers.IO) { store.save(p) } }
    }

    fun savePosition(ms: Long) {
        val p = _state.value.project ?: return
        if (kotlin.math.abs(p.positionMs - ms) < 500) return
        val np = p.copy(positionMs = ms.coerceAtLeast(0L))
        _state.update { it.copy(project = np) }
        viewModelScope.launch(Dispatchers.IO) { store.save(np) }
    }

    private fun clamp(ms: Long): Long {
        val d = _state.value.project?.durationMs ?: 0L
        return if (d > 0) ms.coerceIn(0L, d) else ms.coerceAtLeast(0L)
    }

    private fun addRegion(a: Long, b: Long): Boolean {
        val s = clamp(minOf(a, b))
        val e = clamp(maxOf(a, b))
        if (e - s < MIN_REGION_MS) {
            _state.update { it.copy(error = "Участок слишком короткий") }
            return false
        }
        val r = MuteRegion(System.nanoTime(), s, e)
        update { it.copy(regions = it.regions + r) }
        _state.update { it.copy(selectedId = r.id) }
        return true
    }

    /** «⏺ Начало» во время просмотра. */
    fun markStart(ms: Long) = _state.update { it.copy(pendingStartMs = clamp(ms), selection = null) }

    /** «⏹ Конец»: участок от «Начала» до текущего места. */
    fun markEnd(ms: Long) {
        val start = _state.value.pendingStartMs ?: return
        if (addRegion(start, ms)) _state.update { it.copy(pendingStartMs = null) }
    }

    fun cancelPending() = _state.update { it.copy(pendingStartMs = null) }

    fun setSelection(a: Long, b: Long) =
        _state.update { it.copy(selection = clamp(minOf(a, b)) to clamp(maxOf(a, b)), selectedId = null) }

    fun clearSelection() = _state.update { it.copy(selection = null) }

    fun addSelection() {
        val sel = _state.value.selection ?: return
        if (addRegion(sel.first, sel.second)) _state.update { it.copy(selection = null) }
    }

    fun select(id: Long?) = _state.update { it.copy(selectedId = id, selection = null) }

    /** Сдвиг края участка: [start] — левый край, иначе правый. */
    fun nudge(id: Long, start: Boolean, deltaMs: Long) = update { p ->
        p.copy(regions = p.regions.map { r ->
            if (r.id != id) r
            else if (start) r.copy(startMs = clamp(r.startMs + deltaMs).coerceAtMost(r.endMs - MIN_REGION_MS))
            else r.copy(endMs = clamp(r.endMs + deltaMs).coerceAtLeast(r.startMs + MIN_REGION_MS))
        })
    }

    /** Поставить край участка в текущее место видео. */
    fun setEdge(id: Long, start: Boolean, ms: Long) = update { p ->
        p.copy(regions = p.regions.map { r ->
            if (r.id != id) r
            else if (start) r.copy(startMs = clamp(ms).coerceAtMost(r.endMs - MIN_REGION_MS))
            else r.copy(endMs = clamp(ms).coerceAtLeast(r.startMs + MIN_REGION_MS))
        })
    }

    fun remove(id: Long) {
        update { p -> p.copy(regions = p.regions.filter { it.id != id }) }
        _state.update { it.copy(selectedId = null) }
    }

    fun rename(name: String) {
        val n = name.trim()
        if (n.isNotEmpty()) update { it.copy(name = n) }
    }
}
