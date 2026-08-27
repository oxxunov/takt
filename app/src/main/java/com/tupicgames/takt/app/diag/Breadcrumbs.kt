package com.tupicgames.takt.app.diag

import android.content.Context
import java.io.File

/**
 * Журнал пройденных шагов запуска.
 *
 * Падение в нативном коде (SIGSEGV в C++) убивает процесс мимо любых
 * обработчиков Java — ни стектрейса, ни диалога не остаётся. Единственный
 * способ понять, где оборвалось, — записывать пройденные шаги на диск
 * СРАЗУ, до того как выполнится следующий.
 *
 * Если приложение упало, при следующем запуске последняя запись покажет
 * последний успешно пройденный шаг, а значит следующий за ним и есть
 * виновник.
 */
object Breadcrumbs {

    private const val FILE = "steps.txt"
    private const val DONE = "ЗАВЕРШЕНО"

    @Volatile private var file: File? = null

    fun begin(context: Context, scope: String) {
        val f = File(context.applicationContext.filesDir, FILE)
        file = f
        runCatching { f.writeText("$scope\n") }
    }

    /** Записать шаг. Пишем и сбрасываем на диск немедленно. */
    fun step(name: String) {
        val f = file ?: return
        runCatching { f.appendText("  $name\n") }
    }

    /** Помечает, что участок пройден целиком и падения не было. */
    fun end() {
        val f = file ?: return
        runCatching { f.appendText("$DONE\n") }
    }

    /** @return журнал, если прошлый запуск оборвался, иначе null. */
    fun unfinished(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return if (text.trimEnd().endsWith(DONE)) null else text
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE).delete()
    }
}
