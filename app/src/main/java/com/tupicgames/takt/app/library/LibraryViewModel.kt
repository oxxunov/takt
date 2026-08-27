package com.tupicgames.takt.app.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.tupicgames.takt.core.generator.BeatmapGenerator
import com.tupicgames.takt.core.model.Analysis
import com.tupicgames.takt.core.model.Beatmap
import com.tupicgames.takt.engine.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LibraryState {
    data object Idle : LibraryState
    data class Analyzing(val title: String) : LibraryState
    data class Ready(
        val info: TrackInfo,
        val analysis: Analysis,
        val preview: Beatmap,
        val fromCache: Boolean
    ) : LibraryState
    data class Error(val message: String) : LibraryState
    data object NoBeats : LibraryState
}

/**
 * Держит выбранный трек и его анализ.
 *
 * Раньше анализ запускался через thread {} прямо в Activity: задача не
 * отменялась при выходе и держала ссылку на контекст, а при повороте экрана
 * стартовала заново. viewModelScope снимает и то, и другое.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TrackRepository(app, AppGraph.analysisCache(app))
    private val prefs = AppGraph.prefs(app)

    private val _state = MutableLiveData<LibraryState>(LibraryState.Idle)
    val state: LiveData<LibraryState> = _state

    private var job: Job? = null

    fun select(uri: Uri) {
        job?.cancel()
        val info = repo.describe(uri)
        _state.value = LibraryState.Analyzing(info.title)

        job = viewModelScope.launch {
            // IO, а не Default: декодирование — это ввод-вывод, а пул
            // Default размером с число ядер он занимает целиком.
            val outcome = withContext(Dispatchers.IO) { repo.analyze(info) }
            _state.value = when (outcome) {
                is AnalysisOutcome.Ready ->
                    LibraryState.Ready(info, outcome.analysis, preview(outcome.analysis), outcome.fromCache)
                AnalysisOutcome.NoBeats -> LibraryState.NoBeats
                is AnalysisOutcome.Failed -> LibraryState.Error(outcome.message)
            }
        }
    }

    /**
     * Пересобрать превью после смены сложности или плотности.
     * Дёшево — анализ уже в памяти, повторного разбора файла нет.
     */
    fun refreshPreview() {
        val cur = _state.value as? LibraryState.Ready ?: return
        _state.value = cur.copy(preview = preview(cur.analysis))
    }

    private fun preview(a: Analysis): Beatmap = BeatmapGenerator.generate(
        a, prefs.difficulty, LANES, prefs.sensitivityPercent / 100f, prefs.holdsEnabled
    )

    private companion object {
        const val LANES = 4
    }
}
