package com.oxxunov.voiceenhance.dialogue

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.oxxunov.voiceenhance.R
import com.oxxunov.voiceenhance.engine.RemovalStrength
import com.oxxunov.voiceenhance.ui.ChoiceRow
import com.oxxunov.voiceenhance.ui.fmtTime
import java.io.File

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
    }
    return uri.lastPathSegment ?: "video"
}

@Composable
fun DialogueScreen(vm: DialogueViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pick(uri, displayName(context, uri))
    }
    val saveMp4 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }
    val saveWebm = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/webm")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val result = s.resultPath?.let { File(it) }
    val media: Uri? = if (s.showResult && result != null) Uri.fromFile(result) else s.uri
    LaunchedEffect(media) {
        // A/B: при переключении оригинал ⇄ результат позиция и воспроизведение сохраняются
        val pos = player.currentPosition
        val wasPlaying = player.isPlaying
        val keep = player.mediaItemCount > 0
        player.stop()
        player.clearMediaItems()
        if (media != null) {
            player.setMediaItem(MediaItem.fromUri(media))
            player.prepare()
            if (keep) player.seekTo(pos)
            player.playWhenReady = wasPlaying
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dlg_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.dlg_privacy), style = MaterialTheme.typography.bodySmall)

            if (s.error != null || s.cancelled || s.saved) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when {
                                s.error != null -> stringResource(R.string.dlg_failed, s.error!!)
                                s.cancelled -> stringResource(R.string.dlg_cancelled)
                                else -> stringResource(R.string.dlg_saved)
                            },
                            color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        OutlinedButton(onClick = { vm.dismiss() }) { Text(stringResource(R.string.dlg_ok)) }
                    }
                }
            }

            if (s.uri == null || (!s.running && s.resultPath == null)) {
                Button(
                    onClick = { pick.launch(arrayOf("video/*")) },
                    enabled = !s.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.dlg_pick)) }
            }

            val info = s.info
            if (media != null && info != null) {
                val rotated = info.rotation == 90 || info.rotation == 270
                val w = if (rotated) info.height else info.width
                val h = if (rotated) info.width else info.height
                val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.4f, 2.5f) else 16f / 9f
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player; useController = true } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                )
            }

            if (s.probing) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (info != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.dlg_info_duration, fmtTime(info.durationUs / 1e6)))
                        Text(stringResource(R.string.dlg_info_resolution, info.width, info.height))
                        if (info.sizeBytes > 0) Text(stringResource(R.string.dlg_info_size, Formatter.formatShortFileSize(context, info.sizeBytes)))
                        if (info.audioTracks.isEmpty()) {
                            Text(stringResource(R.string.dlg_info_audio_no), color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.dlg_info_audio_yes, info.audioTracks.size))
                        }
                        if (info.audioTracks.size > 1 && !s.running && s.resultPath == null) {
                            Text(stringResource(R.string.dlg_track_choose), style = MaterialTheme.typography.bodySmall)
                            ChoiceRow(
                                options = info.audioTracks.map { it.index },
                                selected = s.track ?: -1,
                                label = { idx ->
                                    val t = info.audioTracks.first { it.index == idx }
                                    context.getString(
                                        R.string.dlg_track_label,
                                        info.audioTracks.indexOf(t) + 1,
                                        t.language ?: t.mime.substringAfter('/'),
                                        t.sampleRate,
                                        t.channels,
                                    )
                                },
                                onSelect = { vm.selectTrack(it) },
                            )
                        }
                    }
                }

                if (!s.running && s.resultPath == null && info.audioTracks.isNotEmpty()) {
                    Text(stringResource(R.string.dlg_mode_title), style = MaterialTheme.typography.titleSmall)
                    val modes = if (s.banditAvailable) listOf(DialogueWorker.MODE_FAST, DialogueWorker.MODE_STANDARD, DialogueWorker.MODE_HIGH)
                    else listOf(DialogueWorker.MODE_FAST)
                    ChoiceRow(
                        options = modes,
                        selected = s.mode,
                        label = {
                            context.getString(
                                when (it) {
                                    DialogueWorker.MODE_STANDARD -> R.string.dlg_mode_standard
                                    DialogueWorker.MODE_HIGH -> R.string.dlg_mode_high
                                    else -> R.string.dlg_mode_fast
                                }
                            )
                        },
                        onSelect = { vm.setMode(it) },
                    )
                    Text(
                        stringResource(
                            when (s.mode) {
                                DialogueWorker.MODE_STANDARD -> R.string.dlg_mode_standard_note
                                DialogueWorker.MODE_HIGH -> R.string.dlg_mode_high_note
                                else -> R.string.dlg_mode_fast_note
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.dlg_strength_title), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        options = RemovalStrength.entries,
                        selected = s.strength,
                        label = {
                            context.getString(
                                when (it) {
                                    RemovalStrength.LOW -> R.string.dlg_strength_low
                                    RemovalStrength.MEDIUM -> R.string.dlg_strength_medium
                                    RemovalStrength.HIGH -> R.string.dlg_strength_high
                                }
                            )
                        },
                        onSelect = { vm.setStrength(it) },
                    )
                    Text(stringResource(R.string.dlg_strength_note), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
                        },
                        enabled = s.track != null,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(stringResource(R.string.dlg_start), style = MaterialTheme.typography.titleMedium) }
                }
            }

            if (s.running) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${DialogueWorker.stageText(context, s.stage)} ${s.pct}%", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { s.pct / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.dlg_background_note), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.dlg_cancel)) }
                    }
                }
            }

            if (!s.running && result != null && result.exists()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.dlg_done), style = MaterialTheme.typography.titleMedium)
                        Text(result.name, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.dlg_ab_title), style = MaterialTheme.typography.bodySmall)
                        ChoiceRow(
                            options = listOf(false, true),
                            selected = s.showResult,
                            label = { context.getString(if (it) R.string.dlg_ab_result else R.string.dlg_ab_original) },
                            onSelect = { vm.showResult(it) },
                        )
                        Button(
                            onClick = { if (result.extension == "webm") saveWebm.launch(result.name) else saveMp4.launch(result.name) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.dlg_save)) }
                        OutlinedButton(
                            onClick = { vm.shareIntent()?.let { context.startActivity(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.dlg_share)) }
                        OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.dlg_pick_other))
                        }
                    }
                }
                if (s.stems.isNotEmpty()) {
                    ManualRegions(vm, s) { player.currentPosition * 1000L }
                }
            }

            s.report?.let { rep ->
                if (!s.running) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.dlg_report_title), style = MaterialTheme.typography.titleSmall)
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(rep, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Text(stringResource(R.string.dlg_attribution), style = MaterialTheme.typography.labelSmall)
        }
    }
}


private fun fmtUs(us: Long): String {
    val t = us / 100_000 // десятые доли секунды
    return "%d:%02d.%d".format(t / 600, (t / 10) % 60, t % 10)
}

@Composable
private fun stemLabel(name: String): String = stringResource(
    when (name) {
        "speech" -> R.string.dlg_stem_speech
        "music" -> R.string.dlg_stem_music
        "effects" -> R.string.dlg_stem_effects
        else -> R.string.dlg_stem_other
    }
)

/** Ручная правка участков: там, где модель ошиблась (шёпот, крик за кадром), громкости задаются вручную. */
@Composable
private fun ManualRegions(vm: DialogueViewModel, s: DialogueUi, positionUs: () -> Long) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
                Text((if (open) "▾ " else "▸ ") + stringResource(R.string.dlg_manual_title))
            }
            if (!open) return@Column
            Text(stringResource(R.string.dlg_manual_hint), style = MaterialTheme.typography.bodySmall)

            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.markStart(positionUs()) }) {
                    Text(stringResource(R.string.dlg_mark_start, s.draftStartUs?.let { fmtUs(it) } ?: "—"))
                }
                OutlinedButton(onClick = { vm.markEnd(positionUs()) }) {
                    Text(stringResource(R.string.dlg_mark_end, s.draftEndUs?.let { fmtUs(it) } ?: "—"))
                }
            }
            androidx.compose.foundation.layout.Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(onClick = { vm.applyDraftPreset(DialogueViewModel.RegionPreset.REMOVE_WHISPER) }) {
                    Text(stringResource(R.string.dlg_preset_whisper))
                }
                OutlinedButton(onClick = { vm.applyDraftPreset(DialogueViewModel.RegionPreset.RESTORE) }) {
                    Text(stringResource(R.string.dlg_preset_restore))
                }
                OutlinedButton(onClick = { vm.applyDraftPreset(DialogueViewModel.RegionPreset.SILENCE) }) {
                    Text(stringResource(R.string.dlg_preset_silence))
                }
            }
            for (name in s.stems) {
                val g = s.draftGains[name] ?: 1f
                Text("${stemLabel(name)}: ${(g * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = g, onValueChange = { vm.setDraftGain(name, it) }, valueRange = 0f..1f)
            }
            Button(
                onClick = { vm.addRegion() },
                enabled = s.draftStartUs != null && s.draftEndUs != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.dlg_region_add)) }

            val labels = s.stems.associateWith { stemLabel(it) }
            s.regions.forEachIndexed { i, r ->
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    val desc = s.stems.joinToString(", ") { n -> "${labels[n]} ${((r.gains[n] ?: 1f) * 100).toInt()}%" }
                    Text("${fmtUs(r.startUs)}–${fmtUs(r.endUs)}: $desc", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.TextButton(onClick = { vm.removeRegion(i) }) { Text("✕") }
                }
            }
            Button(
                onClick = { vm.applyRegions() },
                enabled = !s.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.dlg_region_apply)) }
            Text(stringResource(R.string.dlg_region_apply_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}
