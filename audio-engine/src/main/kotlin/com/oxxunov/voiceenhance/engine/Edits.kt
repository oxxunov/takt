package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Огибающая для отрисовки: пары (min, max) моно-смеси на каждые [samplesPerBin] кадров. */
object Waveform {
    const val SAMPLES_PER_BIN = 256

    fun peaks(pcm: PcmFile, samplesPerBin: Int = SAMPLES_PER_BIN, isCancelled: () -> Boolean = { false }): FloatArray {
        val nBins = ((pcm.frames + samplesPerBin - 1) / samplesPerBin).toInt()
        val out = FloatArray(nBins * 2)
        val ch = pcm.channels
        val block = samplesPerBin * 64
        val buf = Array(ch) { FloatArray(block) }
        var bin = 0
        var cnt = 0
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        PcmReader(pcm).use { r ->
            while (true) {
                if (isCancelled()) throw ProcessingCancelledException()
                val n = r.read(buf, block)
                if (n <= 0) break
                for (i in 0 until n) {
                    var m = 0f
                    for (c in 0 until ch) m += buf[c][i]
                    m /= ch
                    if (m < lo) lo = m
                    if (m > hi) hi = m
                    if (++cnt == samplesPerBin) {
                        out[2 * bin] = lo; out[2 * bin + 1] = hi
                        bin++; cnt = 0; lo = Float.MAX_VALUE; hi = -Float.MAX_VALUE
                    }
                }
            }
        }
        if (cnt > 0 && bin < nBins) {
            out[2 * bin] = lo; out[2 * bin + 1] = hi
        }
        return out
    }
}

/** Неразрушающие правки: каждая создаёт новый файл, исходный остаётся для Undo. */
object AudioEdits {
    private const val BLOCK = 8192

    /** Оставляет только кадры [start, end). */
    fun trim(pcm: PcmFile, start: Long, end: Long, out: File): PcmFile {
        val s = start.coerceIn(0, pcm.frames)
        val e = end.coerceIn(s, pcm.frames)
        val buf = Array(pcm.channels) { FloatArray(BLOCK) }
        PcmReader(pcm).use { r ->
            r.seek(s)
            PcmWriter(out, pcm.sampleRate, pcm.channels).use { w ->
                var left = e - s
                while (left > 0) {
                    val n = r.read(buf, min(BLOCK.toLong(), left).toInt())
                    if (n <= 0) break
                    w.write(buf, 0, n)
                    left -= n
                }
            }
        }
        return PcmFile(out, pcm.sampleRate, pcm.channels)
    }

    /**
     * Плавное нарастание (fadeIn) или затухание громкости на участке [start, end) по кривой
     * «приподнятый косинус». Для fadeIn всё до start становится тишиной, для fadeOut — всё после end.
     */
    fun fade(pcm: PcmFile, start: Long, end: Long, fadeIn: Boolean, out: File): PcmFile {
        val s = start.coerceIn(0, pcm.frames)
        val e = end.coerceIn(s, pcm.frames)
        val len = max(1L, e - s).toDouble()
        val buf = Array(pcm.channels) { FloatArray(BLOCK) }
        PcmReader(pcm).use { r ->
            PcmWriter(out, pcm.sampleRate, pcm.channels).use { w ->
                var pos = 0L
                while (true) {
                    val n = r.read(buf, BLOCK)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        val f = pos + i
                        val g = when {
                            f < s -> if (fadeIn) 0.0 else 1.0
                            f >= e -> if (fadeIn) 1.0 else 0.0
                            else -> {
                                val t = (f - s) / len
                                val up = 0.5 - 0.5 * cos(PI * t)
                                if (fadeIn) up else 1.0 - up
                            }
                        }
                        if (g != 1.0) for (c in buf.indices) buf[c][i] = (buf[c][i] * g).toFloat()
                    }
                    w.write(buf, 0, n)
                    pos += n
                }
            }
        }
        return PcmFile(out, pcm.sampleRate, pcm.channels)
    }

    /**
     * Сведение многоканального звука в стерео.
     * 5.1 (порядок Android: L R C LFE Ls Rs) и 7.1 — по ITU-R BS.775: центр и тылы с весом −3 дБ, LFE не берётся.
     * Другие раскладки: чётные каналы — влево, нечётные — вправо.
     * Результат нормируется так, чтобы сумма весов не давала перегруза.
     */
    fun downmixToStereo(pcm: PcmFile, out: File, isCancelled: () -> Boolean = { false }): PcmFile {
        val ch = pcm.channels
        require(ch > 2) { "Уже моно или стерео" }
        val k = 0.70710677f
        val wl = FloatArray(ch)
        val wr = FloatArray(ch)
        when (ch) {
            6 -> { wl[0] = 1f; wr[1] = 1f; wl[2] = k; wr[2] = k; wl[4] = k; wr[5] = k }
            8 -> { wl[0] = 1f; wr[1] = 1f; wl[2] = k; wr[2] = k; wl[4] = k; wr[5] = k; wl[6] = k; wr[7] = k }
            else -> for (c in 0 until ch) if (c % 2 == 0) wl[c] = 1f else wr[c] = 1f
        }
        val norm = 1f / maxOf(wl.sum(), wr.sum())
        for (c in 0 until ch) { wl[c] *= norm; wr[c] *= norm }
        val buf = Array(ch) { FloatArray(BLOCK) }
        val st = Array(2) { FloatArray(BLOCK) }
        PcmReader(pcm).use { r ->
            PcmWriter(out, pcm.sampleRate, 2).use { w ->
                while (true) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    val n = r.read(buf, BLOCK)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        var l = 0f
                        var rr = 0f
                        for (c in 0 until ch) {
                            l += buf[c][i] * wl[c]
                            rr += buf[c][i] * wr[c]
                        }
                        st[0][i] = l
                        st[1][i] = rr
                    }
                    w.write(st, 0, n)
                }
            }
        }
        return PcmFile(out, pcm.sampleRate, 2)
    }
}
