package com.oxxunov.voiceenhance.mute

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.oxxunov.voiceenhance.audioio.AudioImporter
import com.oxxunov.voiceenhance.engine.ContainerRules
import com.oxxunov.voiceenhance.engine.MuteMixer
import com.oxxunov.voiceenhance.engine.MuteSpan
import com.oxxunov.voiceenhance.engine.MuteZone
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import com.oxxunov.voiceenhance.engine.WavFormat
import com.oxxunov.voiceenhance.engine.WavWriter
import com.oxxunov.voiceenhance.ml.Resampler
import com.oxxunov.voiceenhance.ml.VoiceRemover
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Что получилось после обработки: звук результата и всё, что нужно для сохранения видео. */
data class RenderInfo(
    /** Подпись участков, по которым считали; не совпадает с текущей — результат устарел. */
    val signature: String,
    val audioFile: String,
    val firstPtsUs: Long,
    val durationUs: Long,
    val videoTrack: Int,
    val rotation: Int,
    /** null — видео нельзя сохранить без перекодирования (или это аудиофайл). */
    val container: ContainerRules.Container?,
    val note: String?,
    val createdAt: Long,
) {
    val hasVideo get() = videoTrack >= 0

    fun toJson(): JSONObject = JSONObject()
        .put("sig", signature).put("file", audioFile).put("pts", firstPtsUs).put("dur", durationUs)
        .put("vt", videoTrack).put("rot", rotation).put("cont", container?.name ?: "")
        .put("note", note ?: "").put("at", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = RenderInfo(
            o.getString("sig"), o.getString("file"), o.getLong("pts"), o.getLong("dur"), o.getInt("vt"), o.getInt("rot"),
            o.optString("cont").takeIf { it.isNotEmpty() }?.let { ContainerRules.Container.valueOf(it) },
            o.optString("note").takeIf { it.isNotEmpty() }, o.optLong("at"),
        )
    }
}

/**
 * Обработка проекта «Заглушки».
 * Модель работает только на участках (+0,5 с запаса с каждой стороны), фон для каждого куска
 * хранится в папке проекта — при правке одного участка пересчитывается только он.
 * Ползунок «Оставить голос» модель не перезапускает: это только смешивание.
 */
class MuteRenderer(private val context: Context, private val store: MuteStore) {

    companion object {
        /** Запас вокруг участка для модели, с. */
        private const val PAD_S = 0.5
        /** Плавный переход на краях участка (снаружи), с. */
        private const val RAMP_S = 0.08
        private const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC

        fun signature(p: MuteProject, model: String): String =
            p.regions.joinToString(";") { "${it.startMs}-${it.endMs}-${"%.3f".format(java.util.Locale.ROOT, it.keepVoice)}" } + "|" + model
    }

    fun modelAvailable() = VoiceRemover.isAvailable(context)

    /** Отпечаток модели: при обновлении модели старые посчитанные куски выбрасываются. */
    fun modelStamp(): String = try {
        context.assets.open("deux/deux.json").bufferedReader().use { it.readText() }.hashCode().toString(16)
    } catch (_: Exception) {
        "none"
    }

    private fun cacheDir(id: String) = File(context.cacheDir, "mute/$id").apply { mkdirs() }

    /** Освободить место: декодированный звук видео (сотни МБ) — пересоздаётся при следующей обработке. */
    fun dropCache(id: String) {
        File(context.cacheDir, "mute/$id").deleteRecursively()
    }

    fun loadInfo(id: String): RenderInfo? {
        val f = File(store.dir(id), "render.json")
        if (!f.exists()) return null
        val info = runCatching { RenderInfo.fromJson(JSONObject(f.readText())) }.getOrNull() ?: return null
        return if (File(store.dir(id), info.audioFile).exists()) info else null
    }

    fun resultFile(id: String, info: RenderInfo) = File(store.dir(id), info.audioFile)

    // ---------------- звук видео (кеш) ----------------

    private class Orig(val pcm: PcmFile, val firstPtsUs: Long)

    private fun orig(p: MuteProject, m: MediaInfo, progress: (Float) -> Unit, isCancelled: () -> Boolean): Orig {
        val dir = cacheDir(p.id)
        val f = File(dir, "orig.f32")
        val meta = File(dir, "orig.json")
        if (f.exists() && meta.exists()) {
            runCatching {
                val o = JSONObject(meta.readText())
                if (o.getString("uri") == p.uri && o.getLong("len") == f.length()) {
                    return Orig(PcmFile(f, o.getInt("sr"), o.getInt("ch")), o.getLong("pts"))
                }
            }
        }
        f.delete(); meta.delete()
        val need = (m.durationUs / 1e6 * 48000 * 2 * 4).toLong() + (100L shl 20)
        val free = dir.usableSpace
        if (free in 1 until need) throw IOException("Недостаточно места: нужно около ${need shr 20} МБ, свободно ${free shr 20} МБ")
        val d = AudioImporter(context).decodeTrack(Uri.parse(p.uri), m.audioTrack, f, progress, isCancelled, m.durationUs)
        meta.writeText(
            JSONObject().put("uri", p.uri).put("sr", d.pcm.sampleRate).put("ch", d.pcm.channels)
                .put("pts", d.firstPtsUs).put("len", f.length()).toString()
        )
        return Orig(d.pcm, d.firstPtsUs)
    }

    private fun zones(p: MuteProject, o: Orig): List<MuteZone> {
        val sr = o.pcm.sampleRate
        val total = o.pcm.frames
        fun frame(ms: Long) = ((ms * 1000L - o.firstPtsUs) * sr / 1_000_000L).coerceIn(0L, total)
        return p.regions.map { MuteZone(frame(it.startMs), frame(it.endMs), it.keepVoice) }.filter { it.endFrame > it.startFrame }
    }

    private fun bgDir(id: String, o: Orig): File {
        val dir = File(store.dir(id), "bg").apply { mkdirs() }
        val stamp = "${modelStamp()}/${o.pcm.sampleRate}/${o.pcm.channels}/${o.firstPtsUs}"
        val sf = File(dir, "stamp.txt")
        if (!sf.exists() || sf.readText() != stamp) {
            dir.deleteRecursively()
            dir.mkdirs()
            sf.writeText(stamp)
        }
        return dir
    }

    private fun bgFile(dir: File, s: MuteSpan) = File(dir, "bg_${s.start}_${s.end}.f32")

    // ---------------- обработка ----------------

    /**
     * Полная обработка: звук видео → фон без голоса для новых кусков → смешивание → AAC.
     * [progress] получает подпись шага и долю 0..1 всей работы.
     */
    fun render(p: MuteProject, progress: (String, Float) -> Unit, isCancelled: () -> Boolean): RenderInfo {
        if (p.regions.isEmpty()) throw IOException("Нет ни одного участка")
        val uri = Uri.parse(p.uri)
        val m = MediaTools.probe(context, uri)
        progress("Чтение звука", 0f)
        val o = orig(p, m, { progress("Чтение звука", 0.15f * it) }, isCancelled)
        val sr = o.pcm.sampleRate
        val ch = o.pcm.channels
        val zones = zones(p, o)
        val spans = MuteMixer.plan(zones, (PAD_S * sr).toLong(), o.pcm.frames)
        val dir = bgDir(p.id, o)
        val wanted = spans.map { bgFile(dir, it).name }.toSet()
        dir.listFiles()?.forEach { if (it.name.startsWith("bg_") && it.name !in wanted) it.delete() }

        val todo = spans.filter { s -> bgFile(dir, s).let { !it.exists() || it.length() != s.frames * 4L * ch } }
        if (todo.isNotEmpty()) {
            if (!modelAvailable()) throw IOException("Модель удаления голоса не вошла в эту сборку")
            val totalFrames = todo.sumOf { it.frames }.toDouble()
            var doneFrames = 0L
            progress("Загрузка модели", 0.15f)
            VoiceRemover(context).use { vr ->
                for ((k, s) in todo.withIndex()) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    val label = "Удаление голоса: кусок ${k + 1} из ${todo.size}"
                    computeSpan(o.pcm, s, vr, bgFile(dir, s), {
                        progress(label, 0.15f + 0.7f * ((doneFrames + it * s.frames) / totalFrames).toFloat())
                    }, isCancelled)
                    doneFrames += s.frames
                }
            }
        }

        // ---------- смешивание и кодирование ----------
        val plan = if (m.videoTrack >= 0) ContainerRules.plan(m.videoMime, Build.VERSION.SDK_INT) else null
        var note: String? = null
        var container = plan?.container
        var mime = plan?.audioMime ?: AAC
        if (m.videoTrack >= 0 && plan == null) {
            note = "Видео в формате ${m.videoMime.substringAfter('/')} нельзя сохранить без перекодирования — доступно сохранение звука (WAV)."
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS && sr !in ContainerRules.OPUS_RATES) {
            note = "Для WEBM нужен звук 48 кГц, в файле $sr Гц — доступно сохранение звука (WAV)."
            container = null
            mime = AAC
        }
        val kbps = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) 160 else 256
        val work = cacheDir(p.id)
        progress("Сборка звука", 0.86f)
        val delay = MediaTools.encoderDelay(context, mime, sr, ch, kbps, work)
        val name = "result." + if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) "webm" else "m4a"
        val tmp = File(store.dir(p.id), "$name.tmp")
        val backgrounds = spans.map { it to PcmFile(bgFile(dir, it), sr, ch) }
        MuteMixer.open(o.pcm, backgrounds, zones, (RAMP_S * sr).toLong(), advance = delay.toLong()).use { src ->
            MediaTools.encode(src, tmp, mime, kbps, { progress("Сборка звука", 0.86f + 0.14f * it) }, isCancelled)
        }
        store.dir(p.id).listFiles()?.forEach { if (it.name.startsWith("result.") && it.name != tmp.name) it.delete() }
        val dst = File(store.dir(p.id), name)
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw IOException("Не удалось сохранить результат")
        }
        val info = RenderInfo(
            signature(p, modelStamp()), name, o.firstPtsUs, m.durationUs, m.videoTrack, m.rotation, container, note,
            System.currentTimeMillis(),
        )
        File(store.dir(p.id), "render.json").writeText(info.toJson().toString())
        progress("Готово", 1f)
        return info
    }

    /** Фон без голоса для одного куска — в частоте и каналах исходника, длина — кадр в кадр. */
    private fun computeSpan(
        orig: PcmFile,
        s: MuteSpan,
        vr: VoiceRemover,
        out: File,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val sr = orig.sampleRate
        val ch = orig.channels
        val len = s.frames.toInt()
        val dir = out.parentFile!!
        val tIn = File(dir, "t_in.f32")
        val tM = File(dir, "t_m.f32")
        val tB = File(dir, "t_b.f32")
        val tBack = File(dir, "t_back.f32")
        try {
            // кусок исходника → стерео (моно дублируется, больше двух каналов — среднее)
            val x = Array(ch) { FloatArray(len) }
            PcmReader(orig).use { r -> r.seek(s.start); r.read(x, len) }
            val st = if (ch == 2) x else {
                val mono = FloatArray(len) { i -> var a = 0f; for (c in 0 until ch) a += x[c][i]; a / ch }
                arrayOf(mono, mono)
            }
            PcmWriter(tIn, sr, 2).use { it.write(st, 0, len) }
            val inM = if (sr == vr.sampleRate) PcmFile(tIn, sr, 2)
            else Resampler.file(PcmFile(tIn, sr, 2), vr.sampleRate, tM, isCancelled = isCancelled)
            val bgM = vr.background(inM, tB, { progress(it * 0.97f) }, isCancelled)
            val back = if (sr == vr.sampleRate) bgM
            else Resampler.file(bgM, sr, tBack, isCancelled = isCancelled, targetFrames = len.toLong())
            val y = Array(2) { FloatArray(len) }
            PcmReader(back).use { it.read(y, len) }
            val res = if (ch == 2) y else {
                // голос = смесь − фон, вычитается из каждого канала одинаково
                Array(ch) { c -> FloatArray(len) { i -> x[c][i] - (st[0][i] - 0.5f * (y[0][i] + y[1][i])) } }
            }
            val tmpOut = File(dir, out.name + ".tmp")
            PcmWriter(tmpOut, sr, ch).use { it.write(res, 0, len) }
            out.delete()
            if (!tmpOut.renameTo(out)) throw IOException("Не удалось сохранить кусок")
            progress(1f)
        } finally {
            tIn.delete(); tM.delete(); tB.delete(); tBack.delete()
        }
    }

    // ---------------- сохранение ----------------

    /** Видео с новым звуком: кадры видео копируются без перекодирования. */
    fun exportVideo(p: MuteProject, info: RenderInfo, target: Uri, progress: (Float) -> Unit, isCancelled: () -> Boolean) {
        val container = info.container ?: throw IOException(info.note ?: "Это видео нельзя сохранить без перекодирования")
        val audio = resultFile(p.id, info)
        val tmp = File(cacheDir(p.id), "export." + container.ext)
        try {
            val srcSize = runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(p.uri), "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            if (srcSize > 0 && tmp.parentFile!!.usableSpace in 1 until srcSize + (50L shl 20)) {
                throw IOException("Недостаточно места: нужно около ${(srcSize shr 20) + 50} МБ")
            }
            val aLast = MediaTools.remux(
                context, Uri.parse(p.uri), info.videoTrack, info.rotation, audio, info.firstPtsUs, info.durationUs,
                container, tmp, { progress(0.9f * it) }, isCancelled,
            )
            if (info.durationUs > 0 && info.durationUs - aLast > 2_000_000L) {
                throw IOException("Звук собрался не полностью — попробуйте ещё раз")
            }
            val os = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Не удалось создать файл")
            os.use { out ->
                tmp.inputStream().use { inp ->
                    val buf = ByteArray(1 shl 20)
                    val total = tmp.length().coerceAtLeast(1)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(0.9f + 0.1f * done / total)
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /** Звук результата без сжатия (WAV 24-bit). Требует актуальной обработки. */
    fun exportWav(p: MuteProject, target: Uri, progress: (Float) -> Unit, isCancelled: () -> Boolean) {
        val m = MediaTools.probe(context, Uri.parse(p.uri))
        val o = orig(p, m, { progress(0.3f * it) }, isCancelled)
        val sr = o.pcm.sampleRate
        val ch = o.pcm.channels
        val zones = zones(p, o)
        val spans = MuteMixer.plan(zones, (PAD_S * sr).toLong(), o.pcm.frames)
        val dir = bgDir(p.id, o)
        val bgs = spans.map { s ->
            val f = bgFile(dir, s)
            if (!f.exists()) throw IOException("Участки изменились — сначала нажмите «Удалить голос»")
            s to PcmFile(f, sr, ch)
        }
        val mixed = File(cacheDir(p.id), "mixed.f32")
        try {
            val pcm = MuteMixer.render(o.pcm, bgs, zones, (RAMP_S * sr).toLong(), mixed, { progress(0.3f + 0.3f * it) }, isCancelled)
            val os = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Не удалось создать файл")
            os.use { WavWriter.write(pcm, it, WavFormat.PCM24, { f -> progress(0.6f + 0.4f * f) }, isCancelled) }
        } finally {
            mixed.delete()
        }
    }
}
