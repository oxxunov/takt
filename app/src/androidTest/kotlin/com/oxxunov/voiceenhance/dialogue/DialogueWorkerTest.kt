package com.oxxunov.voiceenhance.dialogue

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.oxxunov.voiceenhance.video.SyntheticVideo
import com.oxxunov.voiceenhance.video.VideoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DialogueWorkerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    // 11. фоновая обработка: задача WorkManager выполняется целиком и чистит временные файлы
    @Test
    fun workerProducesResultAndCleansTemp() {
        val src = SyntheticVideo.create(ctx, File(ctx.cacheDir, "wk.mp4"), 4.0, withAudio = true)
        val track = VideoProbe.probe(ctx, Uri.fromFile(src)).audioTracks[0].index
        val worker = TestListenableWorkerBuilder<DialogueWorker>(ctx)
            .setInputData(
                workDataOf(
                    DialogueWorker.KEY_URI to Uri.fromFile(src).toString(),
                    DialogueWorker.KEY_TRACK to track,
                    DialogueWorker.KEY_NAME to "wk.mp4",
                    DialogueWorker.KEY_MODE to DialogueWorker.MODE_FAST,
                    DialogueWorker.KEY_STRENGTH to "MEDIUM",
                )
            )
            .build()
        val result = runBlocking { worker.doWork() }
        assertTrue("результат: $result", result is ListenableWorker.Result.Success)
        val path = result.outputData.getString(DialogueWorker.KEY_RESULT)
        assertNotNull(path)
        assertTrue(File(path!!).exists())
        assertTrue(File(path).name == "wk_no_dialogues.mp4")
        val tmp = DialogueWorker.tempDir(ctx)
        assertTrue("временные файлы удалены", !tmp.exists() || tmp.listFiles().isNullOrEmpty())
    }

    // 12. восстановление после закрытия приложения: выбранное видео и дорожка возвращаются
    @Test
    fun viewModelRestoresSelection() {
        val src = SyntheticVideo.create(ctx, File(ctx.cacheDir, "rs.mp4"), 2.0, withAudio = true)
        val track = VideoProbe.probe(ctx, Uri.fromFile(src)).audioTracks[0].index
        ctx.getSharedPreferences("dialogue", 0).edit()
            .putString("uri", Uri.fromFile(src).toString()).putString("name", "rs.mp4").putInt("track", track).commit()
        var vm: DialogueViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = DialogueViewModel(ctx as Application) }
        val deadline = System.currentTimeMillis() + 15_000
        while (vm!!.state.value.info == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val s = vm!!.state.value
        assertEquals(Uri.fromFile(src), s.uri)
        assertEquals("rs.mp4", s.name)
        assertEquals(track, s.track)
        assertNotNull(s.info)
        ctx.getSharedPreferences("dialogue", 0).edit().clear().commit()
    }
}
