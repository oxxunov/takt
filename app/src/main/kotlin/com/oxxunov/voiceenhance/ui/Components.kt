package com.oxxunov.voiceenhance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oxxunov.voiceenhance.engine.AudioAnalysis
import java.util.Locale

fun fmt(v: Double, digits: Int = 1): String =
    if (v.isInfinite() || v.isNaN() || v < -200) "—" else String.format(Locale.US, "%.${digits}f", v)

fun fmtTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (o in options) {
            if (o == selected) {
                Button(onClick = { onSelect(o) }, enabled = enabled) { Text(label(o)) }
            } else {
                OutlinedButton(onClick = { onSelect(o) }, enabled = enabled) { Text(label(o)) }
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    valueText: String,
    onChange: (Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}

@Composable
fun Section(
    title: String,
    enabled: Boolean?,
    onToggle: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((if (expanded) "▾ " else "▸ ") + title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (enabled != null) Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (expanded) {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
            }
        }
    }
}

@Composable
fun MetricsCard(original: AudioAnalysis?, enhanced: AudioAnalysis?) {
    if (original == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("Показатель", Modifier.weight(1.4f), fontWeight = FontWeight.SemiBold)
                Text("Оригинал", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (enhanced != null) Text("Обработка", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            }
            MetricRow("Громкость, LUFS", fmt(original.lufs), enhanced?.let { fmt(it.lufs) })
            MetricRow("Peak, dBFS", fmt(original.peakDb), enhanced?.let { fmt(it.peakDb) })
            MetricRow("True Peak, dBTP", fmt(original.truePeakDb), enhanced?.let { fmt(it.truePeakDb) })
            MetricRow("RMS, dB", fmt(original.rmsDb), enhanced?.let { fmt(it.rmsDb) })
            MetricRow("Шумовой фон, dB", fmt(original.noiseFloorDb), enhanced?.let { fmt(it.noiseFloorDb) })
            MetricRow("Уровень речи, dB", fmt(original.speechLevelDb), enhanced?.let { fmt(it.speechLevelDb) })
            MetricRow("SNR (оценка), dB", fmt(original.snrDb), enhanced?.let { fmt(it.snrDb) })
            MetricRow("Клиппинг, сэмплов", original.clippedSamples.toString(), enhanced?.clippedSamples?.toString())
            Spacer(Modifier.width(1.dp))
            Text(
                "${original.sampleRate} Гц · ${if (original.channels == 1) "моно" else "стерео"} · ${fmtTime(original.durationSec)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MetricRow(name: String, a: String, b: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
        Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (b != null) Text(b, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
