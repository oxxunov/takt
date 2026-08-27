package com.tupicgames.takt.engine.analysis

import android.content.Context
import java.io.File

/**
 * Перехват нативных падений.
 *
 * Падение в C++ убивает процесс мимо обработчиков Java — снаружи это
 * выглядит просто как "приложение выкинуло", и никаких следов не остаётся.
 * Нативный обработчик сигналов успевает записать причину и трассировку
 * на диск до смерти процесса.
 */
object NativeDiagnostics {

    private const val FILE = "native_crash.txt"

    init { NativeLibrary.ensureLoaded() }

    fun install(context: Context): Boolean {
        val f = File(context.applicationContext.filesDir, FILE)
        return runCatching { nativeInstallCrashHandler(f.absolutePath) }.getOrDefault(false)
    }

    /** @return посмертный отчёт нативного слоя, если он есть. */
    fun read(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE).delete()
    }

    external fun nativeInstallCrashHandler(path: String): Boolean
}
