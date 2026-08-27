package com.tupicgames.takt.engine.data

import android.content.Context
import android.net.Uri
import com.tupicgames.takt.core.io.AnalysisIo
import com.tupicgames.takt.core.model.Analysis
import java.io.File
import java.security.MessageDigest

/**
 * Кэш результатов анализа в приватном каталоге приложения.
 *
 * Хранится результат анализа, а не готовая раскладка: анализ занимает
 * секунды, генерация раскладки — микросекунды. Смена сложности или
 * плотности пересобирает уровень мгновенно, без повторного разбора файла.
 *
 * Сам аудиофайл никуда не копируется и не покидает устройство.
 */
class AnalysisCache(context: Context) {

    private val dir = File(context.filesDir, "analysis").apply { mkdirs() }

    fun keyFor(uri: Uri, sizeBytes: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(uri.toString().toByteArray())
        md.update(sizeBytes.toString().toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun load(key: String): Analysis? {
        val f = File(dir, "$key$EXT")
        if (!f.exists()) return null
        return runCatching { f.inputStream().use { AnalysisIo.read(it) } }.getOrNull()
    }

    fun save(key: String, analysis: Analysis) {
        // Пишем во временный файл и переименовываем: иначе прерванная запись
        // оставит обрезанный кэш, который потом молча не прочитается.
        val tmp = File(dir, "$key.tmp")
        runCatching {
            tmp.outputStream().use { AnalysisIo.write(it, analysis) }
            tmp.renameTo(File(dir, "$key$EXT"))
        }.onFailure { tmp.delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private companion object {
        const val EXT = ".rga"
    }
}
