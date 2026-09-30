package com.oxxunov.voiceenhance.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.oxxunov.voiceenhance.engine.VoicePresets
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxxunov.voiceenhance.audioio.AudioRecorder
import com.oxxunov.voiceenhance.engine.EqPresets
import com.oxxunov.voiceenhance.engine.LoudnessTarget
import com.oxxunov.voiceenhance.engine.WavFormat
import kotlin.math.max

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val name = c.getString(0)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment ?: "audio"
}

@Composable
fun EnhanceScreen(vm: EnhanceViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val playing by vm.player.playing.collectAsStateWithLifecycle()
    val position by vm.player.position.collectAsStateWithLifecycle()
    val level by vm.recorder.level.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri, displayName(context, uri))
    }
    val exportWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val exportM4a = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val exportMp3 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.openRecorder() else vm.showError("Без доступа к микрофону запись невозможна")
    }
    val busy = s.busy != null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Voice Enhance", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { vm.undo() }, enabled = s.canUndo && !busy) { Text("↶ Отменить") }
                TextButton(onClick = { vm.redo() }, enabled = s.canRedo && !busy) { Text("↷ Вернуть") }
            }

            if (s.error != null || s.message != null) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.error ?: s.message ?: "",
                            Modifier.weight(1f),
                            color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        OutlinedButton(onClick = { vm.dismissMessages() }) { Text("OK") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (ok) vm.openRecorder() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = !busy && !s.recorder.open,
                    modifier = Modifier.weight(1f),
                ) { Text("🎙 Запись") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("audio/*")) },
                    enabled = !busy && !s.recorder.open,
                    modifier = Modifier.weight(1f),
                ) { Text("📂 Импорт") }
            }

            if (s.recorder.open) RecorderPanel(vm, s.recorder, level)

            s.busy?.let { b ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${b.label}… ${(b.progress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { vm.cancel() }) { Text("Отмена") }
                    }
                }
            }

            val original = s.original
            if (original != null) {
                Text(s.originalName, style = MaterialTheme.typography.titleMedium)
                MetricsCard(s.originalAnalysis, s.enhancedAnalysis)

                // ---- волна, прослушивание, правки ----
                val src = if (s.listenEnhanced && s.enhanced != null) s.enhanced!! else original
                val peaks = if (s.listenEnhanced && s.enhancedPeaks != null) s.enhancedPeaks else s.originalPeaks
                EditorCard(vm, s, src, peaks, position, playing, busy)

                EnhancePanel(vm, s, busy)

                SettingsPanel(vm, s)

                // ---- экспорт ----
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Экспорт", style = MaterialTheme.typography.titleSmall)
                        ChoiceRow(ExportKind.entries, s.exportKind, { it.label }, { vm.setExportKind(it) })
                        if (s.exportKind == ExportKind.WAV) {
                            ChoiceRow(WavFormat.entries, s.exportFormat, { it.label }, { vm.setExportFormat(it) })
                            Text("WAV сохраняется без дополнительных потерь.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            ChoiceRow(listOf(128, 192, 256, 320), s.exportKbps, { "$it кбит/с" }, { vm.setExportKbps(it) })
                        }
                        Button(
                            onClick = {
                                val base = s.originalName.substringBeforeLast('.').ifBlank { "voice" }
                                val name = "${base}_enhanced.${s.exportKind.ext}"
                                when (s.exportKind) {
                                    ExportKind.WAV -> exportWav.launch(name)
                                    ExportKind.M4A -> exportM4a.launch(name)
                                    ExportKind.MP3 -> exportMp3.launch(name)
                                }
                            },
                            enabled = !busy && s.enhanced != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("💾 Сохранить ${s.exportKind.label}") }
                        if (s.enhanced == null) {
                            Text("Сначала нажмите ✨ ENHANCE или «Обработать»", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecorderPanel(vm: EnhanceViewModel, r: RecorderUi, level: AudioRecorder.Level) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Запись", style = MaterialTheme.typography.titleSmall)
            if (level.active) {
                Text("Источник: ${level.sourceLabel} · ${level.sampleRate} Гц", style = MaterialTheme.typography.bodySmall)
            }
            if (!level.recording) {
                ChoiceRow(listOf(44100, 48000), r.sampleRate, { "${it / 1000.0} кГц" }, { vm.setRecorderFormat(it, r.channels, r.bitDepth) })
                ChoiceRow(listOf(1, 2), r.channels, { if (it == 1) "Моно" else "Стерео" }, { vm.setRecorderFormat(r.sampleRate, it, r.bitDepth) })
                ChoiceRow(listOf(WavFormat.PCM16, WavFormat.PCM24), r.bitDepth, { it.label }, { vm.setRecorderFormat(r.sampleRate, r.channels, it) })
                Text(
                    "Захват идёт в float; разрядность применяется при сохранении WAV.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val frac = ((level.peakDb + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
            Text("Уровень: ${fmt(level.peakDb)} dBFS", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(10.dp))
            if (level.clipped) {
                Text(
                    "⚠ Перегрузка! Отодвиньте телефон или говорите тише.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (level.recording) Text("● Запись ${fmtTime(level.seconds)}", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!level.recording) {
                    Button(onClick = { vm.startRecording() }, enabled = level.active) { Text("● Начать") }
                } else {
                    Button(onClick = { vm.stopRecording() }) { Text("■ Стоп") }
                }
                OutlinedButton(onClick = { vm.closeRecorder() }) { Text("Закрыть") }
            }
        }
    }
}

@Composable
private fun SettingsPanel(vm: EnhanceViewModel, s: UiState) {
    val st = s.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Настройки обработки", style = MaterialTheme.typography.titleSmall)

        val nr = st.noiseReduction
        Section("Шумоподавление (DeepFilterNet3)", nr.enabled, { v -> vm.updateSettings { it.copy(noiseReduction = it.noiseReduction.copy(enabled = v)) } }) {
            LabeledSlider(
                "Макс. подавление",
                nr.attenLimitDb,
                6.0..100.0,
                if (nr.attenLimitDb >= 99.5) "без ограничения" else "${fmt(nr.attenLimitDb, 0)} дБ",
            ) { v ->
                vm.updateSettings { it.copy(noiseReduction = it.noiseReduction.copy(attenLimitDb = Math.round(v).toDouble())) }
            }
            Text(
                "Нейросеть убирает вентилятор, гул, шипение, фон комнаты и улицы. Работает офлайн. " +
                    "Меньше дБ — мягче и естественнее, фон остаётся слегка слышен.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val dr = st.deReverb
        Section("De-Reverb (WPE)", dr.enabled, { v -> vm.updateSettings { it.copy(deReverb = it.deReverb.copy(enabled = v)) } }) {
            LabeledSlider("Сила", dr.strength, 0.0..100.0, "${fmt(dr.strength, 0)}%") { v ->
                vm.updateSettings { it.copy(deReverb = it.deReverb.copy(strength = Math.round(v).toDouble())) }
            }
            Text(
                "Убирает эхо и «гулкость» комнаты, не трогая прямой голос и ранние отражения. " +
                    "Обработка тяжёлая: на телефоне может занимать десятки секунд на минуту записи.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val hp = st.highPass
        Section("Low Cut (High-pass)", hp.enabled, { v -> vm.updateSettings { it.copy(highPass = it.highPass.copy(enabled = v)) } }) {
            LabeledSlider("Частота среза", hp.frequencyHz, 20.0..300.0, "${fmt(hp.frequencyHz, 0)} Гц") { v ->
                vm.updateSettings { it.copy(highPass = it.highPass.copy(frequencyHz = v)) }
            }
            ChoiceRow(listOf(false, true), hp.slope24, { if (it) "24 дБ/окт" else "12 дБ/окт" }, { v ->
                vm.updateSettings { it.copy(highPass = it.highPass.copy(slope24 = v)) }
            })
        }

        val eq = st.eq
        Section("Эквалайзер", eq.enabled, { v -> vm.updateSettings { it.copy(eq = it.eq.copy(enabled = v)) } }) {
            ChoiceRow(EqPresets.all.map { it.name }, eq.presetName, { it }, { name ->
                val p = EqPresets.all.first { it.name == name }
                vm.updateSettings {
                    it.copy(
                        eq = it.eq.copy(presetName = p.name, bands = EqPresets.bands(p)),
                        highPass = it.highPass.copy(frequencyHz = p.highPassHz),
                    )
                }
            })
            eq.bands.forEachIndexed { i, b ->
                val f = if (b.frequencyHz >= 1000) "${fmt(b.frequencyHz / 1000.0, if (b.frequencyHz % 1000.0 == 0.0) 0 else 1)} кГц"
                else "${fmt(b.frequencyHz, 0)} Гц"
                LabeledSlider(f, b.gainDb, -12.0..12.0, "${fmt(b.gainDb)} дБ") { g ->
                    vm.updateSettings { cur ->
                        cur.copy(
                            eq = cur.eq.copy(
                                presetName = "Своя",
                                bands = cur.eq.bands.mapIndexed { j, bb -> if (j == i) bb.copy(gainDb = Math.round(g * 2) / 2.0) else bb },
                            )
                        )
                    }
                }
            }
        }

        val c = st.compressor
        Section("Компрессор", c.enabled, { v -> vm.updateSettings { it.copy(compressor = it.compressor.copy(enabled = v)) } }) {
            ChoiceRow(listOf(true, false), c.auto, { if (it) "Auto" else "Вручную" }, { v ->
                vm.updateSettings { it.copy(compressor = it.compressor.copy(auto = v)) }
            })
            if (c.auto) {
                Text("Порог — от измеренного уровня речи, ratio 3:1, attack 8 мс, release 150 мс, knee 6 дБ.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                fun upd(t: (com.oxxunov.voiceenhance.engine.CompressorSettings) -> com.oxxunov.voiceenhance.engine.CompressorSettings) =
                    vm.updateSettings { it.copy(compressor = t(it.compressor)) }
                LabeledSlider("Threshold", c.thresholdDb, -60.0..0.0, "${fmt(c.thresholdDb)} дБ") { v -> upd { it.copy(thresholdDb = v) } }
                LabeledSlider("Ratio", c.ratio, 1.0..20.0, "${fmt(c.ratio)}:1") { v -> upd { it.copy(ratio = v) } }
                LabeledSlider("Attack", c.attackMs, 0.1..100.0, "${fmt(c.attackMs)} мс") { v -> upd { it.copy(attackMs = v) } }
                LabeledSlider("Release", c.releaseMs, 10.0..1000.0, "${fmt(c.releaseMs, 0)} мс") { v -> upd { it.copy(releaseMs = v) } }
                LabeledSlider("Knee", c.kneeDb, 0.0..24.0, "${fmt(c.kneeDb)} дБ") { v -> upd { it.copy(kneeDb = v) } }
                LabeledSlider("Makeup Gain", c.makeupDb, 0.0..24.0, "${fmt(c.makeupDb)} дБ") { v -> upd { it.copy(makeupDb = v) } }
            }
        }

        val d = st.deEsser
        Section("De-Esser", d.enabled, { v -> vm.updateSettings { it.copy(deEsser = it.deEsser.copy(enabled = v)) } }) {
            LabeledSlider("Сила", d.amount, 0.0..100.0, "${fmt(d.amount, 0)}%") { v ->
                vm.updateSettings { it.copy(deEsser = it.deEsser.copy(amount = v)) }
            }
            LabeledSlider("Частота", d.frequencyHz, 3000.0..10000.0, "${fmt(d.frequencyHz, 0)} Гц") { v ->
                vm.updateSettings { it.copy(deEsser = it.deEsser.copy(frequencyHz = v)) }
            }
        }

        val l = st.limiter
        Section("True Peak Limiter", l.enabled, { v -> vm.updateSettings { it.copy(limiter = it.limiter.copy(enabled = v)) } }) {
            LabeledSlider("Ceiling", l.ceilingDbTp, -6.0..0.0, "${fmt(l.ceilingDbTp)} dBTP") { v ->
                vm.updateSettings { it.copy(limiter = it.limiter.copy(ceilingDbTp = Math.round(v * 10) / 10.0)) }
            }
            LabeledSlider("Output Level", l.outputLevelDb, -12.0..0.0, "${fmt(l.outputLevelDb)} дБ") { v ->
                vm.updateSettings { it.copy(limiter = it.limiter.copy(outputLevelDb = Math.round(v * 10) / 10.0)) }
            }
            LabeledSlider("Release", l.releaseMs, 10.0..500.0, "${fmt(l.releaseMs, 0)} мс") { v ->
                vm.updateSettings { it.copy(limiter = it.limiter.copy(releaseMs = v)) }
            }
        }

        val n = st.loudness
        Section("Нормализация громкости", n.enabled, { v -> vm.updateSettings { it.copy(loudness = it.loudness.copy(enabled = v)) } }) {
            ChoiceRow(LoudnessTarget.entries, n.target, { if (it == LoudnessTarget.AUTO) "Auto" else "${it.label} LUFS" }, { t ->
                vm.updateSettings { it.copy(loudness = it.loudness.copy(target = t)) }
            })
            Text("Auto = −16 LUFS (стандарт для речи и подкастов).", style = MaterialTheme.typography.bodySmall)
        }
    }
}


private fun strengthLabel(v: Double): String = when {
    v < 1 -> "без обработки"
    v < 30 -> "лёгкая коррекция"
    v < 50 -> "стандартная"
    v < 70 -> "сильная очистка"
    v < 90 -> "агрессивная реставрация"
    else -> "максимальная обработка"
}

@Composable
private fun EnhancePanel(vm: EnhanceViewModel, s: UiState, busy: Boolean) {
    var askName by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.autoEnhance() },
                enabled = !busy && !s.recorder.open,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("✨ ENHANCE", style = MaterialTheme.typography.titleLarge) }

            LabeledSlider(
                "Strength",
                s.strength,
                0.0..100.0,
                "${fmt(s.strength, 0)}% · ${strengthLabel(s.strength)}",
            ) { vm.setStrength(Math.round(it).toDouble()) }

            if (s.autoNotes.isNotEmpty()) {
                Text("Подобрано по анализу:", style = MaterialTheme.typography.titleSmall)
                for (n in s.autoNotes) Text("• $n", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Пресеты", style = MaterialTheme.typography.titleSmall)
            val names = VoicePresets.all.map { it.name } + s.userPresets.map { it.name }
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in names) {
                    if (n == s.activePreset) {
                        Button(onClick = { vm.applyPreset(n) }, enabled = !busy) { Text(n) }
                    } else {
                        OutlinedButton(onClick = { vm.applyPreset(n) }, enabled = !busy) { Text(n) }
                    }
                }
            }
            if (s.userPresets.isNotEmpty()) {
                for (p in s.userPresets) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.deleteUserPreset(p.name) }) { Text("✕ удалить") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { name = ""; askName = true }) { Text("Сохранить как пресет") }
                OutlinedButton(onClick = { vm.enhance() }, enabled = !busy && !s.recorder.open) { Text("Обработать") }
            }
            Text(
                "«Обработать» — с текущими настройками (пресет или ручные). ✨ ENHANCE подбирает всё сам.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (askName) {
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("Название пресета") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveUserPreset(name)
                    askName = false
                }, enabled = name.isNotBlank()) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Отмена") } },
        )
    }
}

