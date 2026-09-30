package com.oxxunov.voiceenhance.dialogue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.oxxunov.voiceenhance.R
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import com.oxxunov.voiceenhance.engine.RemovalStrength
import com.oxxunov.voiceenhance.video.BanditSpeechSeparator
import com.oxxunov.voiceenhance.video.DeepFilterSpeechSeparator
import com.oxxunov.voiceenhance.video.DialogueRemover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Фоновая задача удаления диалогов. Работает как foreground-сервис с уведомлением:
 * переживает сворачивание приложения и блокировку экрана. При отмене удаляет временные файлы.
 */
class DialogueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val UNIQUE = "dialogue_removal"
        const val KEY_URI = "uri"
        const val KEY_TRACK = "track"
        const val KEY_NAME = "name"
        const val KEY_MODE = "mode"
        const val MODE_FAST = "fast"
        const val MODE_STANDARD = "standard"
        const val MODE_HIGH = "high"
        const val KEY_STRENGTH = "strength"
        const val KEY_ACTION = "action"
        const val ACTION_PROCESS = "process"
        const val ACTION_RENDER = "render"
        const val KEY_REGIONS = "regions"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_STAGE = "stage"
        const val KEY_PCT = "pct"
        const val ERROR_CANCELLED = "cancelled"
        private const val CHANNEL = "dialogue"
        private const val NOTIF_ID = 4201

        /** Вес этапов в общем проценте. */
        private val WEIGHTS = floatArrayOf(0.1f, 0.6f, 0.1f, 0.2f)

        fun stageText(ctx: Context, stage: Int): String = ctx.getString(
            when (stage) {
                0 -> R.string.dlg_stage_analyze
                1 -> R.string.dlg_stage_separate
                2 -> R.string.dlg_stage_remove
                3 -> R.string.dlg_stage_assemble
                else -> R.string.dlg_stage_done
            }
        )

        fun tempDir(ctx: Context) = File(ctx.cacheDir, "dialogue_tmp")
        fun resultDir(ctx: Context) = File(ctx.filesDir, "dialogue")
        fun projectDir(ctx: Context) = File(resultDir(ctx), "project")

        /** Участки ручной правки ⇄ JSON (передаются в задачу). */
        fun regionsToJson(list: List<DialogueRemover.RegionSpec>): String = org.json.JSONArray().apply {
            for (r in list) put(
                org.json.JSONObject().put("s", r.startUs).put("e", r.endUs)
                    .put("g", org.json.JSONObject().apply { r.gains.forEach { (k, v) -> put(k, v.toDouble()) } })
            )
        }.toString()

        fun regionsFromJson(text: String?): List<DialogueRemover.RegionSpec> = try {
            val a = org.json.JSONArray(text ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val g = o.getJSONObject("g")
                DialogueRemover.RegionSpec(
                    o.getLong("s"), o.getLong("e"),
                    g.keys().asSequence().associateWith { g.getDouble(it).toFloat() },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val uri = Uri.parse(inputData.getString(KEY_URI) ?: return Result.failure(workDataOf(KEY_ERROR to "Нет файла")))
        val track = inputData.getInt(KEY_TRACK, -1)
        val name = inputData.getString(KEY_NAME) ?: "video"
        val separator = when (inputData.getString(KEY_MODE)) {
            MODE_STANDARD -> BanditSpeechSeparator(applicationContext, highQuality = false)
            MODE_HIGH -> BanditSpeechSeparator(applicationContext, highQuality = true)
            else -> DeepFilterSpeechSeparator(applicationContext)
        }
        val strength = runCatching { RemovalStrength.valueOf(inputData.getString(KEY_STRENGTH) ?: "MEDIUM") }
            .getOrDefault(RemovalStrength.MEDIUM)
        try {
            setForeground(foregroundInfo(0, 0))
        } catch (_: Exception) {
            // если система не разрешила foreground (редкие случаи) — продолжаем как обычная задача
        }
        val render = inputData.getString(KEY_ACTION) == ACTION_RENDER
        val tmp = tempDir(applicationContext).apply { deleteRecursively(); mkdirs() }
        val outDir = resultDir(applicationContext).apply {
            if (!render) deleteRecursively() // храним только последний результат
            mkdirs()
        }
        val base = name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "video" }
        var lastPct = -1
        var lastStage = -1
        return try {
            val onProgress: (DialogueRemover.Stage, Float) -> Unit = { stage, p ->
                val st = stage.ordinal
                var done = 0f
                for (i in 0 until st) done += WEIGHTS[i]
                val pct = ((done + WEIGHTS[st] * p.coerceIn(0f, 1f)) * 100).toInt().coerceIn(0, 99)
                if (pct != lastPct || st != lastStage) {
                    lastPct = pct
                    lastStage = st
                    setProgressAsync(workDataOf(KEY_STAGE to st, KEY_PCT to pct))
                    try { setForegroundAsync(foregroundInfo(st, pct)) } catch (_: Exception) {}
                }
            }
            val outNoExt = File(outDir, "${base}_no_dialogues")
            val res = withContext(Dispatchers.Default) {
                if (render) {
                    DialogueRemover(applicationContext).render(
                        uri, projectDir(applicationContext), regionsFromJson(inputData.getString(KEY_REGIONS)),
                        tmp, outNoExt, onProgress, { isStopped },
                    )
                } else {
                    DialogueRemover(applicationContext).run(
                        source = uri,
                        audioTrack = track,
                        separator = separator,
                        strength = strength,
                        workDir = tmp,
                        outFileNoExt = outNoExt,
                        onProgress = onProgress,
                        isCancelled = { isStopped },
                        projectDir = projectDir(applicationContext),
                    )
                }
            }
            Result.success(workDataOf(KEY_RESULT to res.file.absolutePath))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessingCancelledException) {
            Result.failure(workDataOf(KEY_ERROR to ERROR_CANCELLED))
        } catch (e: OutOfMemoryError) {
            Result.failure(workDataOf(KEY_ERROR to "Недостаточно памяти для обработки этого видео"))
        } catch (e: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun foregroundInfo(stage: Int, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, applicationContext.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.notif_title))
            .setContentText("${stageText(applicationContext, stage)} $pct%")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, applicationContext.getString(R.string.dlg_cancel), cancel)
            .build()
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIF_ID, n)
        }
    }
}
