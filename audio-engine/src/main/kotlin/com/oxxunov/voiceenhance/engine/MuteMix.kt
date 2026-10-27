package com.oxxunov.voiceenhance.engine

import java.io.Closeable
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Поток кадров, который можно читать блоками (как [PcmReader]), не создавая файл. */
interface FrameSource : Closeable {
    val sampleRate: Int
    val channels: Int
    val frames: Long
    /** Читает до [maxFrames] кадров в планарный буфер. 0 — конец. */
    fun read(dst: Array<FloatArray>, maxFrames: Int): Int
}

/** Участок, где голос глушится: [startFrame, endFrame) в кадрах звука; [keep] — сколько голоса оставить (0..1). */
data class MuteZone(val startFrame: Long, val endFrame: Long, val keep: Float)

/** Кусок звука, для которого моделью посчитан фон без голоса: [start, end). */
data class MuteSpan(val start: Long, val end: Long) {
    val frames: Long get() = end - start
}

/**
 * «Заглушка голоса»: вне участков — оригинал без изменений, внутри — фон без голоса от модели.
 * результат = оригинал − w·(оригинал − фон), где w = (1 − keep) внутри участка
 * и плавно (косинус) спадает до 0 за [rampFrames] кадров снаружи от краёв.
 */
object MuteMixer {
    private const val BLOCK = 8192

    /**
     * Куски для модели: каждый участок с запасом [pad] кадров по краям (модели нужен контекст,
     * а переход — снаружи участка); пересекающиеся куски объединяются.
     */
    fun plan(zones: List<MuteZone>, pad: Long, total: Long): List<MuteSpan> {
        val out = ArrayList<MuteSpan>()
        for (z in zones.sortedBy { it.startFrame }) {
            val s = (z.startFrame - pad).coerceIn(0L, total)
            val e = (z.endFrame + pad).coerceIn(0L, total)
            if (e <= s) continue
            val last = out.lastOrNull()
            if (last != null && s <= last.end) out[out.size - 1] = MuteSpan(last.start, max(last.end, e))
            else out += MuteSpan(s, e)
        }
        return out
    }

    /** Сила глушения в кадре [f] (0 — оригинал, 1 — полностью фон). */
    fun weight(f: Long, zones: List<MuteZone>, rampFrames: Long): Float {
        var w = 0f
        for (z in zones) {
            val g = shape(f, z, rampFrames) * (1f - z.keep.coerceIn(0f, 1f))
            if (g > w) w = g
        }
        return w
    }

    private fun shape(f: Long, z: MuteZone, r: Long): Float = when {
        f >= z.startFrame && f < z.endFrame -> 1f
        r <= 0 -> 0f
        f < z.startFrame && f >= z.startFrame - r -> {
            val t = (f - (z.startFrame - r)).toDouble() / r
            (0.5 - 0.5 * cos(PI * t)).toFloat()
        }
        f >= z.endFrame && f < z.endFrame + r -> {
            val t = (f - z.endFrame + 1).toDouble() / r
            (0.5 + 0.5 * cos(PI * t)).toFloat()
        }
        else -> 0f
    }

    /**
     * Смешанный звук как поток. [backgrounds] — фон для каждого куска из [plan] (той же длины, частоты, каналов).
     * [advance] — сдвиг раньше на столько кадров (компенсация задержки кодера); хвост — тишина, длина сохраняется.
     * Глушение действует только внутри кусков с фоном.
     */
    fun open(
        original: PcmFile,
        backgrounds: List<Pair<MuteSpan, PcmFile>>,
        zones: List<MuteZone>,
        rampFrames: Long,
        advance: Long = 0L,
    ): FrameSource = MixSource(original, backgrounds.sortedBy { it.first.start }, zones.sortedBy { it.startFrame }, rampFrames, advance)

    /** То же, но в файл. */
    fun render(
        original: PcmFile,
        backgrounds: List<Pair<MuteSpan, PcmFile>>,
        zones: List<MuteZone>,
        rampFrames: Long,
        out: File,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PcmFile {
        val ch = original.channels
        val total = original.frames
        try {
            open(original, backgrounds, zones, rampFrames).use { src ->
                PcmWriter(out, original.sampleRate, ch).use { w ->
                    val buf = Array(ch) { FloatArray(BLOCK) }
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        val n = src.read(buf, BLOCK)
                        if (n <= 0) break
                        w.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toDouble() / total).toFloat())
                    }
                }
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return PcmFile(out, original.sampleRate, ch)
    }

    private class MixSource(
        private val original: PcmFile,
        private val bgs: List<Pair<MuteSpan, PcmFile>>,
        private val zones: List<MuteZone>,
        private val ramp: Long,
        private val advance: Long,
    ) : FrameSource {
        override val sampleRate = original.sampleRate
        override val channels = original.channels
        override val frames = original.frames
        private val reader = PcmReader(original).also { it.seek(advance) }
        private val bgReaders = HashMap<Int, PcmReader>()
        /** Позиция выхода (кадр выходного потока). */
        private var pos = 0L
        private var bgBuf = Array(channels) { FloatArray(0) }

        override fun read(dst: Array<FloatArray>, maxFrames: Int): Int {
            val left = frames - pos
            if (left <= 0) return 0
            val n = min(maxFrames.toLong(), left).toInt()
            val got = reader.read(dst, n)
            for (c in 0 until channels) java.util.Arrays.fill(dst[c], got.coerceAtLeast(0), n, 0f)
            // кадры источника этого блока: [a, a + got)
            val a = pos + advance
            val b = a + got
            for ((k, pair) in bgs.withIndex()) {
                val span = pair.first
                val s = max(a, span.start)
                val e = min(b, span.end)
                if (e <= s) continue
                val len = (e - s).toInt()
                if (bgBuf[0].size < len) bgBuf = Array(channels) { FloatArray(len) }
                val r = bgReaders.getOrPut(k) { PcmReader(pair.second) }
                r.seek(s - span.start)
                val g = r.read(bgBuf, len)
                if (g <= 0) continue
                val near = zones.filter { it.endFrame + ramp > s && it.startFrame - ramp < s + g }
                if (near.isEmpty()) continue
                for (i in 0 until g) {
                    val f = s + i
                    val w = weight(f, near, ramp)
                    if (w <= 0f) continue
                    val di = (f - a).toInt()
                    for (c in 0 until channels) {
                        val x = dst[c][di]
                        dst[c][di] = x - w * (x - bgBuf[c][i])
                    }
                }
            }
            pos += n
            return n
        }

        override fun close() {
            reader.close()
            bgReaders.values.forEach { it.close() }
        }
    }
}
