package com.tupicgames.takt.app.diag

import android.content.Context
import com.tupicgames.takt.engine.analysis.NativeDiagnostics

/**
 * Собирает единый отчёт из трёх источников.
 *
 * Каждый закрывает свою слепую зону:
 *  - CrashLog — стектрейс падения Java;
 *  - NativeDiagnostics — сигнал и трассировка нативного падения;
 *  - Breadcrumbs — последний пройденный шаг, если не сработало ни то, ни другое
 *    (процесс убит системой по памяти, ANR и тому подобное).
 */
object DiagnosticsReport {

    fun build(context: Context): String? {
        val java = CrashLog.read(context)
        val native = NativeDiagnostics.read(context)
        val steps = Breadcrumbs.unfinished(context)

        if (java == null && native == null && steps == null) return null

        return buildString {
            appendLine("ТАКТ — отчёт о сбое")
            appendLine("устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine()

            if (native != null) {
                appendLine("═══ НАТИВНЫЙ СЛОЙ ═══")
                appendLine(native)
                appendLine()
            }
            if (java != null) {
                appendLine("═══ СЛОЙ KOTLIN ═══")
                appendLine(java)
                appendLine()
            }
            if (steps != null) {
                appendLine("═══ ПОСЛЕДНИЕ ШАГИ ═══")
                appendLine(steps)
                if (native == null && java == null) {
                    appendLine()
                    appendLine("Ни стектрейса, ни сигнала нет: процесс, вероятно,")
                    appendLine("убит системой (нехватка памяти или зависание).")
                }
            }
        }
    }

    fun hasReport(context: Context): Boolean = build(context) != null

    fun clear(context: Context) {
        CrashLog.clear(context)
        NativeDiagnostics.clear(context)
        Breadcrumbs.clear(context)
    }
}
