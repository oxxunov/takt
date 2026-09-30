package com.oxxunov.voiceenhance.video

import android.content.Context
import android.net.Uri
import android.os.Build
import com.oxxunov.voiceenhance.audioio.AudioImporter
import com.oxxunov.voiceenhance.engine.ContainerRules
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmMath
import com.oxxunov.voiceenhance.engine.PcmWriter
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import com.oxxunov.voiceenhance.engine.MixRegion
import com.oxxunov.voiceenhance.engine.RegionMixer
import java.io.IOException
import kotlin.math.abs

/**
 * Удаление диалогов из видео:
 * извлечение звука → разделение → удаление речи → кодирование → замена дорожки (remux, видео не перекодируется).
 */
class DialogueRemover(private val context: Context) {

    enum class Stage { ANALYZE, SEPARATE, REMOVE, ASSEMBLE }

    class Result(val file: File, val container: ContainerRules.Container)

    /** Участок ручной правки во времени видео и громкости источников по именам (speech/music/effects/other). */
    class RegionSpec(val startUs: Long, val endUs: Long, val gains: Map<String, Float>)

    /** Сохранённые данные обработки для ручной правки без повторного запуска модели. */
    private class Meta(
        val sampleRate: Int, val channels: Int, val firstPtsUs: Long, val videoTrack: Int, val rotation: Int,
        val durationUs: Long, val container: ContainerRules.Container, val audioMime: String, val kbps: Int,
        val stems: List<String>,
    ) {
        fun toJson() = JSONObject()
            .put("sr", sampleRate).put("ch", channels).put("pts", firstPtsUs).put("vt", videoTrack).put("rot", rotation)
            .put("dur", durationUs).put("cont", container.name).put("mime", audioMime).put("kbps", kbps)
            .put("stems", JSONArray(stems))

        companion object {
            fun from(o: JSONObject) = Meta(
                o.getInt("sr"), o.getInt("ch"), o.getLong("pts"), o.getInt("vt"), o.getInt("rot"), o.getLong("dur"),
                ContainerRules.Container.valueOf(o.getString("cont")), o.getString("mime"), o.getInt("kbps"),
                o.getJSONArray("stems").let { a -> (0 until a.length()).map { a.getString(it) } },
            )
        }
    }

    fun run(
        source: Uri,
        audioTrack: Int,
        separator: AudioSeparator,
        strength: com.oxxunov.voiceenhance.engine.RemovalStrength,
        workDir: File,
        outFileNoExt: File,
        onProgress: (Stage, Float) -> Unit,
        isCancelled: () -> Boolean,
        projectDir: File? = null,
    ): Result {
        workDir.mkdirs()
        // ---------- анализ ----------
        onProgress(Stage.ANALYZE, 0f)
        val info = VideoProbe.probe(context, source)
        val track = info.audioTracks.firstOrNull { it.index == audioTrack }
            ?: throw IOException("В видео нет выбранной аудиодорожки")
        val plan = ContainerRules.plan(info.videoMime, Build.VERSION.SDK_INT)
            ?: throw IOException("Видео в формате ${info.videoMime} нельзя сохранить без перекодирования на этой версии Android")
        if (plan.container == ContainerRules.Container.WEBM && track.sampleRate !in ContainerRules.OPUS_RATES) {
            throw IOException("Для WEBM нужен звук 48 кГц, в файле ${track.sampleRate} Гц")
        }
        // оценка места: несколько float-копий звука (включая сохранённые для ручной правки) + размер видео
        val seconds = info.durationUs / 1e6
        val needBytes = (seconds * maxOf(track.sampleRate, 48000) * maxOf(track.channels, 1) * 4 * 10).toLong() +
            maxOf(info.sizeBytes, 0L) * 2
        checkSpace(workDir, needBytes)

        val importer = AudioImporter(context)
        val decoded = importer.decodeTrack(source, audioTrack, File(workDir, "audio.f32"), { onProgress(Stage.ANALYZE, it) }, isCancelled)

        // ---------- разделение ----------
        onProgress(Stage.SEPARATE, 0f)
        val sep = separator.separate(decoded.pcm, workDir, { onProgress(Stage.SEPARATE, it) }, isCancelled)

        // ---------- удаление речи с выбранной силой ----------
        onProgress(Stage.REMOVE, 0f)
        val baseTmp = com.oxxunov.voiceenhance.engine.SpeechRemoval.apply(
            decoded.pcm, sep.speech, strength, File(workDir, "background.f32"),
            { onProgress(Stage.REMOVE, it * 0.3f) }, isCancelled,
        )
        val kbps = if (plan.audioMime == "audio/opus") 160 else 256

        // ---------- сохраняем проект для ручной правки участков ----------
        val base: PcmFile
        if (projectDir != null) {
            projectDir.deleteRecursively()
            projectDir.mkdirs()
            val stems = ArrayList<Pair<String, PcmFile>>()
            for (st in sep.stems) stems += st.name to st.pcm
            if (sep.stems.size == 1) {
                // быстрый режим: второй источник — «фон» = оригинал − речь
                stems += "other" to PcmMath.subtract(decoded.pcm, sep.speech, 1f, File(workDir, "other.f32"), isCancelled)
            }
            val orig = move(decoded.pcm, File(projectDir, "orig.f32"))
            base = move(baseTmp, File(projectDir, "base.f32"))
            for ((name, pcm) in stems) move(pcm, File(projectDir, "stem_$name.f32"))
            val meta = Meta(
                orig.sampleRate, orig.channels, decoded.firstPtsUs, info.videoTrack, info.rotation, info.durationUs,
                plan.container, plan.audioMime, kbps, stems.map { it.first },
            )
            File(projectDir, "meta.json").writeText(meta.toJson().toString())
        } else {
            base = baseTmp
            sep.stems.forEach { it.pcm.file.delete() }
            decoded.pcm.file.delete()
        }

        val out = assemble(
            source, info.videoTrack, info.rotation, info.durationUs, decoded.firstPtsUs, plan.container, plan.audioMime,
            kbps, base, workDir, outFileNoExt, onProgress, isCancelled,
        )
        if (projectDir == null) base.file.delete()
        return out
    }

    /** Ручная правка: пересобирает звук по участкам из сохранённого проекта (модель повторно не запускается). */
    fun render(
        source: Uri,
        projectDir: File,
        regions: List<RegionSpec>,
        workDir: File,
        outFileNoExt: File,
        onProgress: (Stage, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result {
        workDir.mkdirs()
        val metaFile = File(projectDir, "meta.json")
        if (!metaFile.exists()) throw IOException("Нет данных прошлой обработки — обработайте видео заново")
        val meta = Meta.from(JSONObject(metaFile.readText()))
        val orig = PcmFile(File(projectDir, "orig.f32"), meta.sampleRate, meta.channels)
        val base = PcmFile(File(projectDir, "base.f32"), meta.sampleRate, meta.channels)
        val stems = meta.stems.map { PcmFile(File(projectDir, "stem_$it.f32"), meta.sampleRate, meta.channels) }
        fun frame(us: Long) = ((us - meta.firstPtsUs) * meta.sampleRate / 1_000_000L).coerceIn(0L, orig.frames)
        val mix = regions.map { r ->
            MixRegion(frame(r.startUs), frame(r.endUs), FloatArray(meta.stems.size) { k -> r.gains[meta.stems[k]] ?: 1f })
        }.filter { it.endFrame > it.startFrame }
        onProgress(Stage.REMOVE, 0f)
        val mixed = RegionMixer.render(orig, base, stems, mix, File(workDir, "mixed.f32"), { onProgress(Stage.REMOVE, it * 0.3f) }, isCancelled)
        val res = assemble(
            source, meta.videoTrack, meta.rotation, meta.durationUs, meta.firstPtsUs, meta.container, meta.audioMime,
            meta.kbps, mixed, workDir, outFileNoExt, onProgress, isCancelled,
        )
        mixed.file.delete()
        return res
    }

    /** Компенсация задержки кодера, кодирование и замена дорожки в видео. */
    private fun assemble(
        source: Uri, videoTrack: Int, rotation: Int, durationUs: Long, audioStartUs: Long,
        container: ContainerRules.Container, audioMime: String, kbps: Int,
        audio: PcmFile, workDir: File, outFileNoExt: File,
        onProgress: (Stage, Float) -> Unit, isCancelled: () -> Boolean,
    ): Result {
        val delay = encoderDelay(audioMime, audio.sampleRate, audio.channels, kbps, workDir)
        val aligned = if (delay > 0) PcmMath.advance(audio, delay.toLong(), File(workDir, "aligned.f32")) else audio
        val encoded = File(workDir, "audio_new." + if (container == ContainerRules.Container.WEBM) "webm" else "m4a")
        AudioFileEncoder.encode(aligned, encoded, audioMime, kbps, { onProgress(Stage.REMOVE, 0.3f + it * 0.7f) }, isCancelled)
        if (aligned !== audio) aligned.file.delete()

        onProgress(Stage.ASSEMBLE, 0f)
        val out = File(outFileNoExt.parentFile, outFileNoExt.name + "." + container.ext)
        out.parentFile?.mkdirs()
        val tmpOut = File(workDir, "out_tmp." + container.ext)
        VideoRemuxer.remux(
            context, source, videoTrack, rotation, encoded, audioStartUs,
            durationUs, container, tmpOut, { onProgress(Stage.ASSEMBLE, it) }, isCancelled,
        )
        encoded.delete()
        // готовый файл заменяет прежний только после успешной сборки
        move(tmpOut, out)
        return Result(out, container)
    }

    private fun move(src: File, dst: File): File {
        dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        return dst
    }

    private fun move(p: PcmFile, dst: File): PcmFile = PcmFile(move(p.file, dst), p.sampleRate, p.channels)

    /**
     * Задержка «кодер + декодер» на этом устройстве, в сэмплах: кодируем одиночный импульс,
     * декодируем и ищем, где он оказался. Результат кешируется. Без этого звук сдвинулся бы на ~40 мс.
     */
    private fun encoderDelay(mime: String, sr: Int, ch: Int, kbps: Int, workDir: File): Int {
        val key = "$mime/$sr/$ch/$kbps"
        delayCache[key]?.let { return it }
        val len = sr * 2
        val at = sr / 2
        val pcmFile = File(workDir, "cal.f32")
        val enc = File(workDir, "cal." + if (mime == "audio/opus") "webm" else "m4a")
        val dec = File(workDir, "cal_dec.f32")
        val result = try {
            val buf = Array(ch) { FloatArray(len) }
            for (c in 0 until ch) buf[c][at] = 0.8f
            PcmWriter(pcmFile, sr, ch).use { it.write(buf, 0, len) }
            AudioFileEncoder.encode(PcmFile(pcmFile, sr, ch), enc, mime, kbps)
            val back = AudioImporter(context).decodeTrack(Uri.fromFile(enc), 0, dec).pcm
            val y = Array(back.channels) { FloatArray(back.frames.toInt()) }
            com.oxxunov.voiceenhance.engine.PcmReader(back).use { it.read(y, y[0].size) }
            var best = 0
            var bestV = 0f
            for (i in y[0].indices) if (abs(y[0][i]) > bestV) { bestV = abs(y[0][i]); best = i }
            val d = best - at
            if (bestV > 0.1f && d in 0..8192) d else 0
        } catch (_: Exception) {
            0
        } finally {
            pcmFile.delete(); enc.delete(); dec.delete()
        }
        delayCache[key] = result
        return result
    }

    companion object {
        /** Проверка свободного места перед обработкой. */
        fun checkSpace(dir: File, needBytes: Long) {
            if (dir.usableSpace in 1 until needBytes) {
                throw IOException("Недостаточно свободного места: нужно около ${needBytes / (1024 * 1024)} МБ")
            }
        }

        private val delayCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    }
}
