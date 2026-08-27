package com.tupicgames.takt.app.diag

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сохраняет стектрейс упавшего процесса в файл.
 *
 * На телефоне без компьютера logcat не достать, а без стектрейса причина
 * падения угадывается вслепую. Поэтому трейс пишется в приватный каталог
 * и показывается при следующем запуске.
 *
 * Нативные падения (SIGSEGV в C++) сюда НЕ попадают — их обработчик Java
 * не видит. Если файла нет, а приложение вылетело, значит упал нативный слой.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            pw.println("Такт — падение $stamp")
            pw.println("поток: ${thread.name}")
            pw.println("устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            pw.println()
            error.printStackTrace(pw)
        }
        File(context.filesDir, FILE).writeText(sw.toString())
    }

    /** @return текст последнего падения, либо null. */
    fun read(context: Context): String? {
        val f = File(context.filesDir, FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
