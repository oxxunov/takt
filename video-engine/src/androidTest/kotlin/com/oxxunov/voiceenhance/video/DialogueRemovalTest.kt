package com.oxxunov.voiceenhance.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oxxunov.voiceenhance.audioio.AudioImporter
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import com.oxxunov.voiceenhance.engine.RemovalStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.random.Random

/**
 * Инструментальные тесты модуля «Удалить диалоги» (запускаются в CI на эмуляторе).
 * Видео создаются синтетически: вспышка кадра и щелчок в звуке совпадают — по ним проверяется синхронизация.
 */
@RunWith(AndroidJUnit4::class)
class DialogueRemovalTest {
    private lateinit var ctx: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        dir = File(ctx.cacheDir, "dlg_test").apply { deleteRecursively(); mkdirs() }
    }

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256")
        .digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun countVideoSamples(f: File): Int {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(t)
        var n = 0
        while (ex.sampleTime >= 0) { n++; ex.advance() }
        ex.release()
        return n
    }

    /** Время щелчка (с) в звуковой дорожке файла с учётом времени её начала. */
    private fun clickTime(f: File, track: Int): Double {
        val d = AudioImporter(ctx).decodeTrack(Uri.fromFile(f), track, File(dir, "dec_${System.nanoTime()}.f32"))
        val y = Array(d.pcm.channels) { FloatArray(d.pcm.frames.toInt()) }
        PcmReader(d.pcm).use { it.read(y, y[0].size) }
        val sr = d.pcm.sampleRate
        var best = 0
        var bv = 0f
        for (i in (sr * 1.5).toInt() until minOf(y[0].size, (sr * 2.5).toInt())) {
            if (abs(y[0][i]) > bv) { bv = abs(y[0][i]); best = i }
        }
        d.pcm.file.delete()
        return best.toDouble() / sr + d.firstPtsUs / 1e6
    }

    // 1. выбор видео → чтение информации
    @Test
    fun probeReadsInfo() {
        val f = SyntheticVideo.create(ctx, File(dir, "a.mp4"), 4.0, withAudio = true)
        val info = VideoProbe.probe(ctx, Uri.fromFile(f))
        assertEquals(SyntheticVideo.W, info.width)
        assertEquals(SyntheticVideo.H, info.height)
        assertEquals(1, info.audioTracks.size)
        assertTrue(abs(info.durationUs - 4_000_000L) < 150_000)
    }

    // 2. извлечение аудио
    @Test
    fun extractsAudio() {
        val f = SyntheticVideo.create(ctx, File(dir, "b.mp4"), 3.0, withAudio = true)
        val info = VideoProbe.probe(ctx, Uri.fromFile(f))
        val d = AudioImporter(ctx).decodeTrack(Uri.fromFile(f), info.audioTracks[0].index, File(dir, "b.f32"))
        assertEquals(48000, d.pcm.sampleRate)
        assertEquals(2, d.pcm.channels)
        assertTrue(abs(d.pcm.durationSeconds - 3.0) < 0.15)
    }

    // 3. AI-разделение (быстрая модель и BandIt v2)
    @Test
    fun separatorsKeepLengthAndAreFinite() {
        val f = SyntheticVideo.create(ctx, File(dir, "c.mp4"), 4.0, withAudio = true)
        val info = VideoProbe.probe(ctx, Uri.fromFile(f))
        val d = AudioImporter(ctx).decodeTrack(Uri.fromFile(f), info.audioTracks[0].index, File(dir, "c.f32"))
        val separators = mutableListOf<AudioSeparator>(DeepFilterSpeechSeparator(ctx))
        if (com.oxxunov.voiceenhance.ml.BanditModel(ctx).isAvailable()) separators += BanditSpeechSeparator(ctx, false)
        for (s in separators) {
            val w = File(dir, "w_${s.id}").apply { mkdirs() }
            val r = s.separate(d.pcm, w, {}, { false })
            assertEquals(s.id, d.pcm.frames, r.speech.frames)
            val y = Array(r.speech.channels) { FloatArray(r.speech.frames.toInt()) }
            PcmReader(r.speech).use { it.read(y, y[0].size) }
            for (ch in y) for (v in ch) assertFalse(s.id, v.isNaN() || v.isInfinite())
        }
    }

    // 4–6 + интеграционный тест: видео → аудио → разделение → удаление речи → сборка → результат
    @Test
    fun endToEndKeepsSyncDurationAndOriginal() {
        val src = SyntheticVideo.create(ctx, File(dir, "src.mp4"), 6.0, withAudio = true)
        val before = sha(src)
        val info = VideoProbe.probe(ctx, Uri.fromFile(src))
        val res = DialogueRemover(ctx).run(
            Uri.fromFile(src), info.audioTracks[0].index, DeepFilterSpeechSeparator(ctx), RemovalStrength.MEDIUM,
            File(dir, "work"), File(dir, "src_no_dialogues"), { _, _ -> }, { false },
        )
        assertTrue(res.file.exists())
        assertTrue(res.file.name.endsWith("_no_dialogues.mp4"))
        assertEquals("оригинал не должен меняться", before, sha(src))

        val out = VideoProbe.probe(ctx, Uri.fromFile(res.file))
        assertEquals(1, out.audioTracks.size)
        assertEquals(info.width, out.width)
        assertTrue("длительность", abs(out.durationUs - info.durationUs) < 100_000)
        assertEquals("кадры видео копируются без перекодирования", countVideoSamples(src), countVideoSamples(res.file))

        val tSrc = clickTime(src, info.audioTracks[0].index)
        val tOut = clickTime(res.file, out.audioTracks[0].index)
        assertTrue("рассинхрон ${(tOut - tSrc) * 1000} мс", abs(tOut - tSrc) < 0.015)
    }

    // ручная правка участков: пересборка из сохранённого проекта, синхронизация и длительность сохраняются
    @Test
    fun manualRegionsRender() {
        val src = SyntheticVideo.create(ctx, File(dir, "rg.mp4"), 5.0, withAudio = true)
        val info = VideoProbe.probe(ctx, Uri.fromFile(src))
        val project = File(dir, "project")
        val first = DialogueRemover(ctx).run(
            Uri.fromFile(src), info.audioTracks[0].index, DeepFilterSpeechSeparator(ctx), RemovalStrength.MEDIUM,
            File(dir, "work_rg"), File(dir, "rg_no_dialogues"), { _, _ -> }, { false }, projectDir = project,
        )
        assertTrue(File(project, "meta.json").exists())
        val regions = listOf(DialogueRemover.RegionSpec(1_000_000, 3_000_000, mapOf("speech" to 1f, "other" to 1f)))
        val res = DialogueRemover(ctx).render(
            Uri.fromFile(src), project, regions, File(dir, "work_rg2"), File(dir, "rg_no_dialogues"), { _, _ -> }, { false },
        )
        assertEquals(first.file.absolutePath, res.file.absolutePath)
        val out = VideoProbe.probe(ctx, Uri.fromFile(res.file))
        assertTrue(abs(out.durationUs - info.durationUs) < 100_000)
        // в участке «вернуть оригинал» щелчок на 2.0 с совпадает с оригиналом
        val tSrc = clickTime(src, info.audioTracks[0].index)
        val tOut = clickTime(res.file, out.audioTracks[0].index)
        assertTrue("рассинхрон ${(tOut - tSrc) * 1000} мс", abs(tOut - tSrc) < 0.015)
    }

    // 7. отмена
    @Test
    fun cancelStopsAndLeavesNoOutput() {
        val src = SyntheticVideo.create(ctx, File(dir, "x.mp4"), 4.0, withAudio = true)
        val info = VideoProbe.probe(ctx, Uri.fromFile(src))
        var calls = 0
        try {
            DialogueRemover(ctx).run(
                Uri.fromFile(src), info.audioTracks[0].index, DeepFilterSpeechSeparator(ctx), RemovalStrength.MEDIUM,
                File(dir, "work_x"), File(dir, "x_no_dialogues"), { _, _ -> }, { ++calls > 3 },
            )
            fail("ожидалась отмена")
        } catch (_: ProcessingCancelledException) {
        }
        assertFalse(File(dir, "x_no_dialogues.mp4").exists())
    }

    // 8. повреждённый файл
    @Test
    fun corruptedFileGivesClearError() {
        val bad = File(dir, "bad.mp4").apply { writeBytes(Random(1).nextBytes(100_000)) }
        try {
            VideoProbe.probe(ctx, Uri.fromFile(bad))
            fail("ожидалась ошибка")
        } catch (e: IOException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    // 9. видео без звука
    @Test
    fun videoWithoutAudio() {
        val f = SyntheticVideo.create(ctx, File(dir, "mute.mp4"), 2.0, withAudio = false)
        val info = VideoProbe.probe(ctx, Uri.fromFile(f))
        assertTrue(info.audioTracks.isEmpty())
        try {
            DialogueRemover(ctx).run(
                Uri.fromFile(f), -1, DeepFilterSpeechSeparator(ctx), RemovalStrength.MEDIUM,
                File(dir, "work_m"), File(dir, "mute_no_dialogues"), { _, _ -> }, { false },
            )
            fail("ожидалась ошибка")
        } catch (_: IOException) {
        }
    }

    // 10. большой файл: нехватка места даёт понятную ошибку
    @Test
    fun notEnoughSpace() {
        try {
            DialogueRemover.checkSpace(dir, Long.MAX_VALUE / 4)
            fail("ожидалась ошибка")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("места"))
        }
    }
}
