package com.tupicgames.takt.app.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tupicgames.takt.app.R
import com.tupicgames.takt.core.io.AnalysisExport
import com.tupicgames.takt.engine.data.AppGraph

/**
 * Экран диагностики.
 *
 * Отдельный экран, а не только всплывающий диалог: диалог показывается
 * один раз и его легко закрыть не глядя, после чего отчёт уже не достать.
 * Здесь он лежит, пока его явно не удалят.
 */
class DiagnosticsActivity : AppCompatActivity() {

    /**
     * Выгружает разбор последнего трека.
     *
     * Настроить анализатор по синтетическим трекам можно лишь частично:
     * они показывают, что алгоритм работает в принципе, но не как он ведёт
     * себя на настоящей музыке. Этот файл и есть недостающие данные.
     */
    private fun exportLastTrack() {
        val recent = AppGraph.recentTracks(this).list().firstOrNull()
        if (recent == null) {
            Toast.makeText(this, R.string.export_no_track, Toast.LENGTH_LONG).show()
            return
        }
        val analysis = AppGraph.analysisCache(this).load(recent.cacheKey)
        if (analysis == null) {
            Toast.makeText(this, R.string.export_no_analysis, Toast.LENGTH_LONG).show()
            return
        }

        val text = buildString {
            append(AnalysisExport.summary(analysis, recent.title))
            appendLine()
            appendLine("=== ДОЛИ ===")
            append(AnalysisExport.beatsCsv(analysis))
            appendLine()
            appendLine("=== УДАРЫ (первые 300) ===")
            append(AnalysisExport.onsetsCsv(analysis).lineSequence().take(301).joinToString("\n"))
        }

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Такт — разбор: ${recent.title}")
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.diag_share)
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        val body = findViewById<TextView>(R.id.reportText)
        val report = DiagnosticsReport.build(this)
        body.text = report ?: getString(R.string.diag_empty)

        findViewById<Button>(R.id.copyButton).setOnClickListener {
            if (report == null) return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("takt-diag", report))
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.shareButton).setOnClickListener {
            if (report == null) return@setOnClickListener
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Такт — отчёт о сбое")
                        putExtra(Intent.EXTRA_TEXT, report)
                    },
                    getString(R.string.diag_share)
                )
            )
        }

        findViewById<Button>(R.id.exportButton).setOnClickListener { exportLastTrack() }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            DiagnosticsReport.clear(this)
            body.setText(R.string.diag_empty)
        }
    }
}
