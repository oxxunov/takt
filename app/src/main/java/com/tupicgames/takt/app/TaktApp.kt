package com.tupicgames.takt.app

import android.app.Application
import com.tupicgames.takt.app.diag.CrashLog
import com.tupicgames.takt.engine.analysis.NativeDiagnostics

class TaktApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Порядок важен: сначала обработчики Java, потом нативные —
        // нативный требует загрузки библиотеки и может сам не встать.
        CrashLog.install(this)
        runCatching { NativeDiagnostics.install(this) }
    }
}
