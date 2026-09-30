package com.oxxunov.voiceenhance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.Waveform
import kotlin.math.max
import kotlin.math.min

@Composable
fun EditorCard(
    vm: EnhanceViewModel,
    s: UiState,
    src: PcmFile,
    peaks: FloatArray?,
    position: Long,
    playing: Boolean,
    busy: Boolean,
) {
    val total = max(1L, src.frames)
    var viewStart by remember(total) { mutableLongStateOf(0L) }
    var viewLen by remember(total) { mutableLongStateOf(total) }
    val minLen = max(1L, src.sampleRate / 20L) // максимум приближения — 50 мс на экран

    fun zoom(factor: Double) {
        val center = s.selection?.let { (it.first + it.second) / 2 } ?: position.coerceIn(0, total)
        val newLen = (viewLen * factor).toLong().coerceIn(minLen, total)
        viewLen = newLen
        viewStart = (center - newLen / 2).coerceIn(0L, total - newLen)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceRow(
                options = listOf(false, true),
                selected = s.listenEnhanced && s.enhanced != null,
                label = { if (it) "Обработка" else "Оригинал" },
                onSelect = { vm.listen(it) },
            )

            WaveformView(
                peaks = peaks,
                total = total,
                viewStart = viewStart,
                viewLen = viewLen,
                position = position,
                selection = s.selection,
                onTap = { vm.seek(it) },
                onSelect = { a, b -> vm.setSelection(a, b) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { zoom(2.0) }) { Text("−") }
                OutlinedButton(onClick = { zoom(0.5) }) { Text("+") }
                OutlinedButton(onClick = { viewStart = 0; viewLen = total }) { Text("Весь") }
            }
            if (viewLen < total) {
                Slider(
                    value = viewStart.toFloat(),
                    onValueChange = { viewStart = it.toLong().coerceIn(0L, total - viewLen) },
                    valueRange = 0f..(total - viewLen).toFloat(),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.togglePlay() }) { Text(if (playing) "⏸" else "▶") }
                Text(
                    "  ${fmtTime(position.toDouble() / src.sampleRate)} / ${fmtTime(src.durationSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val sel = s.selection
            if (sel == null) {
                Text(
                    "Касание волны — перейти. Проведите пальцем — выделить участок для A/B-повтора, обрезки и fade.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val sr = src.sampleRate.toDouble()
                Text(
                    "Выделено: ${fmtTime(sel.first / sr)}–${fmtTime(sel.second / sr)} (${fmt((sel.second - sel.first) / sr, 2)} с)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.loop) Button(onClick = { vm.toggleLoop() }) { Text("🔁 A/B повтор") }
                    else OutlinedButton(onClick = { vm.toggleLoop() }) { Text("🔁 A/B повтор") }
                    OutlinedButton(onClick = { vm.applyEdit(EnhanceViewModel.Edit.KEEP) }, enabled = !busy) { Text("✂ Оставить") }
                    OutlinedButton(onClick = { vm.applyEdit(EnhanceViewModel.Edit.FADE_IN) }, enabled = !busy) { Text("Fade in") }
                    OutlinedButton(onClick = { vm.applyEdit(EnhanceViewModel.Edit.FADE_OUT) }, enabled = !busy) { Text("Fade out") }
                    OutlinedButton(onClick = { vm.clearSelection() }) { Text("✕") }
                }
                if (s.loop) {
                    Text(
                        "Участок повторяется: переключайте Оригинал/Обработка — позиция сохраняется.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun WaveformView(
    peaks: FloatArray?,
    total: Long,
    viewStart: Long,
    viewLen: Long,
    position: Long,
    selection: Pair<Long, Long>?,
    onTap: (Long) -> Unit,
    onSelect: (Long, Long) -> Unit,
) {
    val waveColor = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val selColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
    val cursor = MaterialTheme.colorScheme.error

    fun frameAt(x: Float, width: Int): Long {
        val w = max(1, width)
        return (viewStart + (x.coerceIn(0f, w.toFloat()) / w * viewLen).toLong()).coerceIn(0L, total)
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .pointerInput(viewStart, viewLen, total) {
                detectTapGestures { off -> onTap(frameAt(off.x, size.width)) }
            }
            .pointerInput(viewStart, viewLen, total) {
                var anchor = 0L
                detectHorizontalDragGestures(
                    onDragStart = { off -> anchor = frameAt(off.x, size.width) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onSelect(anchor, frameAt(change.position.x, size.width))
                    },
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        drawRect(bg)
        if (selection != null && viewLen > 0) {
            val x0 = ((selection.first - viewStart).toFloat() / viewLen * w).coerceIn(0f, w)
            val x1 = ((selection.second - viewStart).toFloat() / viewLen * w).coerceIn(0f, w)
            if (x1 > x0) drawRect(selColor, Offset(x0, 0f), Size(x1 - x0, h))
        }
        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, mid), Offset(w, mid))
        if (peaks != null && peaks.isNotEmpty()) {
            val bins = peaks.size / 2
            val spb = Waveform.SAMPLES_PER_BIN
            val cols = w.toInt()
            for (x in 0 until cols) {
                val f0 = viewStart + (x.toDouble() / cols * viewLen).toLong()
                val f1 = viewStart + ((x + 1).toDouble() / cols * viewLen).toLong()
                val b0 = (f0 / spb).toInt().coerceIn(0, bins - 1)
                val b1 = max(b0 + 1, min(bins, (f1 / spb).toInt()))
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
        if (position in viewStart..(viewStart + viewLen)) {
            val px = (position - viewStart).toFloat() / viewLen * w
            drawLine(cursor, Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
        }
    }
}
