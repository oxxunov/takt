package com.tupicgames.takt.app.diag

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.tupicgames.takt.app.R
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Показывает ошибку вместе со стектрейсом и кнопкой копирования.
 *
 * Молчаливое закрытие экрана с общим текстом «не удалось» не оставляет
 * никаких следов, а без компьютера logcat недоступен. Поэтому причина
 * всегда показывается целиком.
 */
object ErrorDialog {

    fun show(activity: Activity, title: String, error: Throwable, onClose: () -> Unit) {
        val text = describe(error)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(text.take(4000))
            .setCancelable(false)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                copy(activity, text); onClose()
            }
            .setNegativeButton(R.string.close) { _, _ -> onClose() }
            .show()
    }

    fun describe(error: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
            pw.println()
            error.printStackTrace(pw)
        }
        return sw.toString()
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("takt-error", text))
    }
}
