package com.oxxunov.voiceenhance.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oxxunov.voiceenhance.audioio.AudioImporter
import com.oxxunov.voiceenhance.engine.RemovalStrength
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Длинные (2.5 мин) видео разных вариантов MP4, сгенерированные в CI через ffmpeg:
 * обычный, «faststart» и фрагментированный. Файл должен читаться и собираться целиком.
 */
@RunWith(AndroidJUnit4::class)
class LongSourceTest {
    private lateinit var ctx: Context
    private lateinit var dir: File
    private val lengthS = 150.0

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        dir = File(ctx.cacheDir, "long_test").apply { deleteRecursively(); mkdirs() }
    }

    private fun asset(name: String): File {
        val f = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i ->
            f.outputStream().use { i.copyTo(it) }
        }
        return f
    }

    /** Сколько видит системный MediaExtractor — только для журнала, чтобы знать причину. */
    private fun platformLastVideoUs(f: File): Long {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(t)
        var last = 0L
        while (ex.sampleTime >= 0) { last = maxOf(last, ex.sampleTime); ex.advance() }
        ex.release()
        return last
    }

    private fun check(name: String, fullPipeline: Boolean) {
        val f = asset(name)
        val uri = Uri.fromFile(f)
        Log.i("LongSourceTest", "$name: системный MediaExtractor видит видео до ${platformLastVideoUs(f) / 1e6} с")

        val info = VideoProbe.probe(ctx, uri)
        assertTrue("$name: длительность ${info.durationUs / 1e6}", info.durationUs > (lengthS - 1) * 1e6)
        val (_, vLast) = VideoProbe.trackStats(ctx, uri, info.videoTrack)
        assertTrue("$name: видео прочитано до ${vLast / 1e6} с (${info.engine})", vLast > (lengthS - 1) * 1e6)

        val d = AudioImporter(ctx).decodeTrack(uri, info.audioTracks[0].index, File(dir, "$name.f32"))
        val audioS = d.pcm.frames.toDouble() / d.pcm.sampleRate
        assertTrue("$name: звук декодирован $audioS с", audioS > lengthS - 1)

        if (fullPipeline) {
            val res = DialogueRemover(ctx).run(
                uri, info.audioTracks[0].index, DeepFilterSpeechSeparator(ctx), RemovalStrength.MEDIUM,
                File(dir, "work_$name"), File(dir, "${name}_out"), { _, _ -> }, { false },
            )
            val out = VideoProbe.probe(ctx, Uri.fromFile(res.file))
            val (_, oLast) = VideoProbe.trackStats(ctx, Uri.fromFile(res.file), out.videoTrack)
            Log.i("LongSourceTest", "$name: отчёт\n${res.report}")
            assertTrue("$name: результат ${oLast / 1e6} с", oLast > (lengthS - 1) * 1e6)
        }
    }

    @Test fun plainMp4() = check("long_plain.mp4", false)
    @Test fun faststartMp4() = check("long_faststart.mp4", false)
    @Test fun fragmentedMp4() = check("long_fragmented.mp4", true)
}
