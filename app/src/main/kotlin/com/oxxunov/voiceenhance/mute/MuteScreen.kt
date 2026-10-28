package com.oxxunov.voiceenhance.mute

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.annotation.SuppressLint
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import java.io.File
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private fun displayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
    } catch (_: Exception) {
    }
    return uri.lastPathSegment ?: "Видео"
}

/**
 * Источник для плеера: оригинал или результат. У видео результат — картинка оригинала
 * + новый звук (без перекодирования, прямо в плеере).
 */
@SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun buildSource(context: Context, uri: Uri, result: File?, hasVideo: Boolean): MediaSource {
    val f = DefaultMediaSourceFactory(context)
    val orig = f.createMediaSource(MediaItem.fromUri(uri))
    if (result == null) return orig
    val res = f.createMediaSource(MediaItem.fromUri(Uri.fromFile(result)))
    return if (hasVideo) MergingMediaSource(FilteringMediaSource(orig, C.TRACK_TYPE_VIDEO), res) else res
}

/** 83 456 мс → «1:23.4». */
fun fmtMs(ms: Long): String {
    val t = max(0L, ms) / 100
    val h = t / 36000
    val m = (t / 600) % 60
    val s = (t / 10) % 60
    val d = t % 10
    return if (h > 0) "%d:%02d:%02d.%d".format(h, m, s, d) else "%d:%02d.%d".format(m, s, d)
}

@Composable
fun MuteScreen(vm: MuteViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val p = s.project
        if (p == null) ProjectList(vm, s) else ProjectEditor(vm, s, p)
    }
}

// ======================= список проектов =======================

@Composable
private fun ProjectList(vm: MuteViewModel, s: MuteUi) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.create(uri, displayName(context, uri))
    }
    var confirmDelete by remember { mutableStateOf<MuteProject?>(null) }
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Заглушка голоса", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Отметьте места, где персонаж говорит, — там голос будет удалён, музыка и звуки останутся. " +
                "Работа сохраняется сама: можно закрыть приложение и продолжить с того же места.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { pick.launch(arrayOf("video/*", "audio/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("＋ Новый проект — выбрать видео")
        }
        ErrorCard(s.error) { vm.dismissError() }

        if (s.projects.isEmpty()) {
            Text("Проектов пока нет.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Мои проекты", style = MaterialTheme.typography.titleSmall)
            for (p in s.projects) {
                Card(Modifier.fillMaxWidth().clickable { vm.open(p.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            val dur = if (p.durationMs > 0) " · ${fmtMs(p.durationMs).substringBeforeLast('.')}" else ""
                            Text("Участков: ${p.regions.size}$dur", style = MaterialTheme.typography.bodySmall)
                            Text("Изменён: ${df.format(Date(p.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { confirmDelete = p }) { Text("🗑") }
                    }
                }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить проект?") },
            text = { Text("«${p.name}» и все его участки будут удалены. Само видео на телефоне не трогается.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(p.id); confirmDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ErrorCard(error: String?, onDismiss: () -> Unit) {
    if (error == null) return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

// ======================= редактор проекта =======================

@Composable
private fun ProjectEditor(vm: MuteViewModel, s: MuteUi, p: MuteProject) {
    val context = LocalContext.current
    val player = remember(p.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(p.uri)))
            prepare()
            seekTo(p.positionMs)
        }
    }
    var position by remember(p.id) { mutableLongStateOf(p.positionMs) }
    var playing by remember(p.id) { mutableStateOf(false) }
    /** «Прослушать участок»: остановиться здесь. */
    var stopAt by remember(p.id) { mutableStateOf<Long?>(null) }
    var playerError by remember(p.id) { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) vm.savePosition(player.currentPosition)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) vm.setDuration(player.duration.coerceAtLeast(0L))
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = "Видео не открывается: файл удалён, перемещён или нет доступа"
            }
        }
        player.addListener(listener)
        onDispose {
            vm.savePosition(player.currentPosition)
            player.removeListener(listener)
            player.release()
        }
    }
    // позиция для волны и кнопок; сохранение места каждые несколько секунд
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            position = player.currentPosition
            stopAt?.let { if (position >= it) { player.pause(); stopAt = null } }
            if (++tick % 100 == 0) vm.savePosition(position)
            delay(50)
        }
    }

    // «Оригинал / Результат»: меняем источник, место и воспроизведение сохраняются
    val wantResult = if (s.showResult) s.render?.createdAt else null
    var loadedResult by remember(p.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(player, wantResult) {
        if (wantResult == loadedResult) return@LaunchedEffect
        val pos = player.currentPosition
        val play = player.playWhenReady
        val file = if (wantResult != null) vm.resultFile() else null
        player.setMediaSource(buildSource(context, Uri.parse(p.uri), file, s.render?.hasVideo ?: true))
        player.prepare()
        player.seekTo(pos)
        player.playWhenReady = play
        loadedResult = wantResult
    }

    // во время обработки экран не гаснет
    val view = LocalView.current
    val busy = s.busy != null
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    val saveVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(s.render?.container?.mime ?: "video/mp4")
    ) { uri -> if (uri != null) vm.exportVideo(uri) }
    val saveWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) vm.exportWav(uri)
    }

    BackHandler { vm.close(player.currentPosition) }

    val duration = max(1L, if (p.durationMs > 0) p.durationMs else player.duration.coerceAtLeast(1L))
    var viewStart by remember(p.id) { mutableLongStateOf(0L) }
    var viewLen by remember(p.id) { mutableLongStateOf(30_000L) }
    val vLen = viewLen.coerceIn(min(1_000L, duration), duration)
    val vStart = viewStart.coerceIn(0L, max(0L, duration - vLen))
    // во время воспроизведения волна едет за курсором
    LaunchedEffect(position, playing) {
        if (playing && (position < vStart || position > vStart + vLen * 9 / 10)) {
            viewStart = (position - vLen / 10).coerceIn(0L, max(0L, duration - vLen))
        }
    }

    fun seek(ms: Long) {
        stopAt = null
        player.seekTo(ms.coerceIn(0L, duration))
        position = ms.coerceIn(0L, duration)
    }

    fun showRegion(r: MuteRegion) {
        vm.select(r.id)
        if (r.startMs < vStart || r.endMs > vStart + vLen) {
            viewStart = (r.startMs - vLen / 4).coerceIn(0L, max(0L, duration - vLen))
        }
        seek(r.startMs)
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.close(player.currentPosition) }) { Text("← Проекты") }
            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2)
        }
        ErrorCard(s.error ?: playerError) { vm.dismissError(); playerError = null }

        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        if (s.render != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Слушать:", style = MaterialTheme.typography.bodySmall)
                if (s.showResult) OutlinedButton(onClick = { vm.setShowResult(false) }) { Text("Оригинал") }
                else Button(onClick = {}) { Text("● Оригинал") }
                if (s.showResult) Button(onClick = {}) { Text("● Результат") }
                else OutlinedButton(onClick = { vm.setShowResult(true) }) { Text("Результат") }
            }
            if (s.showResult && !s.resultUpToDate) {
                Text(
                    "Результат от прошлой обработки — участки с тех пор менялись.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        s.loading?.let { (label, f) ->
            Text("$label… ${(f * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
        }

        MuteWaveform(
            peaks = s.peaks,
            duration = duration,
            viewStart = vStart,
            viewLen = vLen,
            position = position,
            regions = p.regions,
            selectedId = s.selectedId,
            pendingStart = s.pendingStartMs,
            selection = s.selection,
            onTap = { ms ->
                val hit = p.regions.firstOrNull { ms in it.startMs..it.endMs }
                if (hit != null) vm.select(hit.id) else vm.select(null)
                seek(ms)
            },
            onSelect = { a, b -> vm.setSelection(a, b) },
        )

        // масштаб и прокрутка волны
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val c = position.coerceIn(vStart, vStart + vLen)
                val nl = (vLen * 2).coerceAtMost(duration)
                viewLen = nl; viewStart = (c - nl / 2).coerceIn(0L, max(0L, duration - nl))
            }) { Text("−") }
            OutlinedButton(onClick = {
                val c = position.coerceIn(vStart, vStart + vLen)
                val nl = (vLen / 2).coerceAtLeast(min(1_000L, duration))
                viewLen = nl; viewStart = (c - nl / 2).coerceIn(0L, max(0L, duration - nl))
            }) { Text("+") }
            Text("на экране ${fmtMs(vLen).substringBeforeLast('.')}", style = MaterialTheme.typography.bodySmall)
        }
        if (vLen < duration) {
            Slider(
                value = vStart.toFloat(),
                onValueChange = { viewStart = it.toLong() },
                valueRange = 0f..(duration - vLen).toFloat(),
            )
        }

        // воспроизведение
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { seek(position - 2_000) }) { Text("⏪ 2с") }
            Button(onClick = { stopAt = null; if (player.isPlaying) player.pause() else player.play() }) {
                Text(if (playing) "⏸" else "▶")
            }
            OutlinedButton(onClick = { seek(position + 2_000) }) { Text("2с ⏩") }
            Text("${fmtMs(position)} / ${fmtMs(duration)}", style = MaterialTheme.typography.bodySmall)
        }

        // разметка на ходу
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.markStart(player.currentPosition) }, modifier = Modifier.weight(1f)) { Text("⏺ Начало") }
            Button(
                onClick = { vm.markEnd(player.currentPosition) },
                enabled = s.pendingStartMs != null,
                modifier = Modifier.weight(1f),
            ) { Text("⏹ Конец") }
        }
        s.pendingStartMs?.let { st ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Начало: ${fmtMs(st)} — нажмите «Конец», когда персонаж замолчит", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.cancelPending() }) { Text("✕") }
            }
        }
        s.selection?.let { (a, b) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Выделено ${fmtMs(a)}–${fmtMs(b)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.addSelection() }) { Text("＋ Участок") }
                TextButton(onClick = { vm.clearSelection() }) { Text("✕") }
            }
        }
        if (s.pendingStartMs == null && s.selection == null) {
            Text(
                "Включите видео и жмите «Начало» и «Конец», когда персонаж начинает и заканчивает говорить. " +
                    "Или проведите пальцем по волне. Касание волны — перейти к месту.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // выбранный участок: точная подгонка краёв
        p.regions.firstOrNull { it.id == s.selectedId }?.let { r ->
            val n = p.regions.indexOf(r) + 1
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Участок $n: ${fmtMs(r.startMs)}–${fmtMs(r.endMs)} (${"%.1f".format(r.lengthMs / 1000.0)} с)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    EdgeRow("Начало", { vm.nudge(r.id, true, it) }, { vm.setEdge(r.id, true, player.currentPosition) })
                    EdgeRow("Конец", { vm.nudge(r.id, false, it) }, { vm.setEdge(r.id, false, player.currentPosition) })
                    var keep by remember(r.id) { mutableStateOf(r.keepVoice) }
                    Text("Оставить голос: ${(keep * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = keep,
                        onValueChange = { keep = (it * 100).toInt() / 100f },
                        onValueChangeFinished = { vm.setKeep(r.id, keep) },
                        valueRange = 0f..0.3f,
                        steps = 29,
                    )
                    Text(
                        "0% — голос убран полностью. 5–15% — едва слышный голос, звучит естественнее.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            seek(r.startMs - 500)
                            stopAt = r.endMs + 500
                            player.play()
                        }) { Text("▶ Прослушать") }
                        OutlinedButton(onClick = { vm.remove(r.id) }) { Text("🗑 Удалить") }
                        TextButton(onClick = { vm.select(null) }) { Text("Готово") }
                    }
                }
            }
        }

        ProcessCard(
            s = s,
            regions = p.regions.size,
            onProcess = { vm.process() },
            onCancel = { vm.cancelWork() },
            onSaveVideo = { saveVideo.launch(p.name + "_без_голоса." + (s.render?.container?.ext ?: "mp4")) },
            onSaveWav = { saveWav.launch(p.name + "_без_голоса.wav") },
            onDismissMessage = { vm.dismissMessage() },
        )

        // список участков
        Text("Участки: ${p.regions.size}", style = MaterialTheme.typography.titleSmall)
        if (p.regions.isEmpty()) {
            Text("Пока ни одного.", style = MaterialTheme.typography.bodySmall)
        }
        p.regions.forEachIndexed { i, r ->
            val sel = r.id == s.selectedId
            Card(
                Modifier.fillMaxWidth().clickable { showRegion(r) },
                colors = if (sel) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                else CardDefaults.cardColors(),
            ) {
                val keep = if (r.keepVoice > 0f) "  · голос ${(r.keepVoice * 100).toInt()}%" else ""
                Text(
                    "${i + 1}.  ${fmtMs(r.startMs)} – ${fmtMs(r.endMs)}   (${"%.1f".format(r.lengthMs / 1000.0)} с)$keep",
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ProcessCard(
    s: MuteUi,
    regions: Int,
    onProcess: () -> Unit,
    onCancel: () -> Unit,
    onSaveVideo: () -> Unit,
    onSaveWav: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Удаление голоса", style = MaterialTheme.typography.titleSmall)
            if (!s.modelAvailable) {
                Text(
                    "Модель удаления голоса не вошла в эту сборку (не прошла проверку при сборке). " +
                        "Разметка сохраняется — удалить голос можно будет после обновления.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            val busy = s.busy
            if (busy != null) {
                Text("${busy.first}… ${(busy.second * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { busy.second }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Не закрывайте приложение. Готовые участки запоминаются — после отмены продолжится с того же места.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onCancel) { Text("Отмена") }
                return@Column
            }
            s.message?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ $m", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismissMessage) { Text("OK") }
                }
            }
            val r = s.render
            when {
                regions == 0 -> Text("Сначала отметьте хотя бы один участок.", style = MaterialTheme.typography.bodySmall)
                r == null -> Text(
                    "Модель уберёт голос только в отмеченных участках — остальное видео не трогается. " +
                        "На каждый участок нужно немного времени.",
                    style = MaterialTheme.typography.bodySmall,
                )
                !s.resultUpToDate -> Text(
                    "Участки или ползунки изменились — обработайте снова. Пересчитаются только изменённые участки.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text("Результат готов — сравните «Оригинал» и «Результат» и сохраните.", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onProcess,
                enabled = regions > 0 && !(r != null && s.resultUpToDate),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (r == null) "✂ Удалить голос в участках ($regions)" else "✂ Обработать снова") }
            if (r != null) {
                r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.hasVideo) {
                        Button(
                            onClick = onSaveVideo,
                            enabled = s.resultUpToDate && r.container != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("💾 Видео ${(r.container?.ext ?: "mp4").uppercase()}") }
                    }
                    OutlinedButton(onClick = onSaveWav, enabled = s.resultUpToDate, modifier = Modifier.weight(1f)) {
                        Text("💾 Звук WAV")
                    }
                }
                if (r.hasVideo) {
                    Text(
                        "Видео сохраняется без перекодирования: качество картинки то же, меняется только звук.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EdgeRow(label: String, nudge: (Long) -> Unit, here: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { nudge(-100) }) { Text("−0.1") }
        OutlinedButton(onClick = { nudge(100) }) { Text("+0.1") }
        TextButton(onClick = here) { Text("сюда") }
    }
}

/** Волна звука видео: участки, курсор, «Начало» и выделение. Время — в мс. */
@Composable
private fun MuteWaveform(
    peaks: FloatArray?,
    duration: Long,
    viewStart: Long,
    viewLen: Long,
    position: Long,
    regions: List<MuteRegion>,
    selectedId: Long?,
    pendingStart: Long?,
    selection: Pair<Long, Long>?,
    onTap: (Long) -> Unit,
    onSelect: (Long, Long) -> Unit,
) {
    val waveColor = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val regionColor = Color(0xFFE53935).copy(alpha = 0.28f)
    val regionSel = Color(0xFFE53935).copy(alpha = 0.5f)
    val selColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val cursor = MaterialTheme.colorScheme.error
    val pendingColor = Color(0xFF43A047)

    fun msAt(x: Float, width: Int): Long {
        val w = max(1, width)
        return (viewStart + (x.coerceIn(0f, w.toFloat()) / w * viewLen).toLong()).coerceIn(0L, duration)
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .pointerInput(viewStart, viewLen, duration) {
                detectTapGestures { off -> onTap(msAt(off.x, size.width)) }
            }
            .pointerInput(viewStart, viewLen, duration) {
                var anchor = 0L
                detectHorizontalDragGestures(
                    onDragStart = { off -> anchor = msAt(off.x, size.width) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onSelect(anchor, msAt(change.position.x, size.width))
                    },
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        fun xOf(ms: Long) = ((ms - viewStart).toFloat() / viewLen * w)
        drawRect(bg)
        for (r in regions) {
            if (r.endMs < viewStart || r.startMs > viewStart + viewLen) continue
            val x0 = xOf(r.startMs).coerceIn(0f, w)
            val x1 = xOf(r.endMs).coerceIn(0f, w)
            if (x1 > x0) drawRect(if (r.id == selectedId) regionSel else regionColor, Offset(x0, 0f), Size(x1 - x0, h))
        }
        if (selection != null) {
            val x0 = xOf(selection.first).coerceIn(0f, w)
            val x1 = xOf(selection.second).coerceIn(0f, w)
            if (x1 > x0) drawRect(selColor, Offset(x0, 0f), Size(x1 - x0, h))
        }
        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, mid), Offset(w, mid))
        if (peaks != null && peaks.isNotEmpty()) {
            val bins = peaks.size / 2
            val binMs = MuteViewModel.BIN_MS.toDouble()
            val cols = w.toInt()
            for (x in 0 until cols) {
                val t0 = viewStart + x.toDouble() / cols * viewLen
                val t1 = viewStart + (x + 1).toDouble() / cols * viewLen
                val b0 = (t0 / binMs).toInt().coerceIn(0, bins - 1)
                val b1 = max(b0 + 1, min(bins, (t1 / binMs).toInt()))
                var lo = 0f
                var hi = 0f
                for (b in b0 until b1) {
                    lo = min(lo, peaks[2 * b])
                    hi = max(hi, peaks[2 * b + 1])
                }
                drawLine(
                    waveColor,
                    Offset(x.toFloat(), mid - hi.coerceIn(-1f, 1f) * mid),
                    Offset(x.toFloat(), mid - lo.coerceIn(-1f, 1f) * mid + 1f),
                )
            }
        }
        if (pendingStart != null && pendingStart in viewStart..(viewStart + viewLen)) {
            val px = xOf(pendingStart)
            drawLine(pendingColor, Offset(px, 0f), Offset(px, h), strokeWidth = 4f)
        }
        if (position in viewStart..(viewStart + viewLen)) {
            val px = xOf(position)
            drawLine(cursor, Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
        }
    }
}
