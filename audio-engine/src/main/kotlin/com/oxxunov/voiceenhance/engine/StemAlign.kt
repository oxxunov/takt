package com.oxxunov.voiceenhance.engine

import org.jtransforms.fft.DoubleFFT_1D
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Проверка и исправление совпадения по времени «речь от модели» ↔ «исходный звук».
 * Удаление речи вычитанием работает, только если речь совпадает с оригиналом до сэмпла:
 * сдвиг даже на несколько сэмплов оставляет голос почти целиком. Здесь сдвиг измеряется
 * взаимной корреляцией по самым «речевым» фрагментам и, если он есть, компенсируется.
 */
object StemAlign {
    private const val WIN = 1 shl 16 // ~1.4 с при 44.1–48 кГц

    /**
     * [lag] — на сколько сэмплов речь отстаёт от оригинала (>0 — опаздывает, <0 — спешит).
     * [corr] — насколько речь похожа на часть оригинала при лучшем сдвиге (0…1).
     * [gainAtBest] — во сколько раз лучший сдвиг точнее нулевого.
     */
    class Result(val lag: Int, val corr: Double, val gainAtBest: Double, val speechDb: Double, val mixDb: Double)

    private fun mono(r: PcmReader, start: Long, n: Int, ch: Int, tmp: Array<FloatArray>, dst: DoubleArray) {
        java.util.Arrays.fill(dst, 0.0)
        if (start < 0) return
        r.seek(start)
        val got = r.read(tmp, n)
        for (i in 0 until max(got, 0)) {
            var s = 0.0
            for (c in 0 until ch) s += tmp[c][i]
            dst[i] = s / ch
        }
    }

    fun measure(mix: PcmFile, speech: PcmFile, maxLag: Int = 4096, isCancelled: () -> Boolean = { false }): Result {
        require(mix.sampleRate == speech.sampleRate && mix.channels == speech.channels)
        val ch = mix.channels
        val total = minOf(mix.frames, speech.frames)
        val segLen = WIN + 2 * maxLag
        if (total < segLen + 1) return Result(0, 0.0, 1.0, -120.0, -120.0)
        var n = 1
        while (n < segLen) n = n shl 1
        val fft = DoubleFFT_1D(n.toLong())
        val tmp = Array(ch) { FloatArray(segLen) }
        val m = DoubleArray(segLen)
        val s = DoubleArray(segLen)
        val acc = DoubleArray(2 * maxLag + 1)
        var em = 0.0
        var es = 0.0
        var emAll = 0.0
        var esAll = 0.0

        PcmReader(mix).use { rm ->
            PcmReader(speech).use { rs ->
                // кандидаты по всему файлу; берём самые «речевые»
                val count = 32
                val cand = (0 until count).map { k ->
                    maxLag + (total - segLen - 1) * k / (count - 1)
                }.map { pos ->
                    mono(rs, pos, WIN, ch, tmp, s)
                    var e = 0.0
                    for (i in 0 until WIN) e += s[i] * s[i]
                    mono(rm, pos, WIN, ch, tmp, m)
                    var e2 = 0.0
                    for (i in 0 until WIN) e2 += m[i] * m[i]
                    emAll += e2; esAll += e
                    Triple(pos, e, e2)
                }.sortedByDescending { it.second }.take(8)

                val fa = DoubleArray(n)
                val fb = DoubleArray(n)
                for ((pos, _, _) in cand) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    mono(rm, pos, WIN, ch, tmp, m)
                    mono(rs, pos - maxLag, segLen, ch, tmp, s)
                    java.util.Arrays.fill(fa, 0.0); java.util.Arrays.fill(fb, 0.0)
                    for (i in 0 until WIN) { fa[i] = m[i]; em += m[i] * m[i] }
                    for (i in 0 until segLen) fb[i] = s[i]
                    for (i in maxLag until maxLag + WIN) es += s[i] * s[i]
                    fft.realForward(fa)
                    fft.realForward(fb)
                    // conj(A)·B → r[k] = Σ m[t]·s[t+k]
                    fb[0] = fa[0] * fb[0]
                    fb[1] = fa[1] * fb[1]
                    for (b in 1 until n / 2) {
                        val ar = fa[2 * b]; val ai = fa[2 * b + 1]
                        val br = fb[2 * b]; val bi = fb[2 * b + 1]
                        fb[2 * b] = ar * br + ai * bi
                        fb[2 * b + 1] = ar * bi - ai * br
                    }
                    fft.realInverse(fb, true)
                    for (k in 0..2 * maxLag) acc[k] += fb[k]
                }
            }
        }
        var best = maxLag
        for (k in acc.indices) if (acc[k] > acc[best]) best = k
        val zero = acc[maxLag]
        val corr = if (em > 0 && es > 0) acc[best] / sqrt(em * es) else 0.0
        val gain = if (abs(zero) > 1e-12) acc[best] / abs(zero) else if (acc[best] > 0) 1e9 else 1.0
        fun db(e: Double) = if (e > 0) 10 * log10(e) else -120.0
        return Result(best - maxLag, corr.coerceIn(-1.0, 1.0), gain, db(esAll), db(emAll))
    }

    /** Сдвигает [a] на [lag] сэмплов раньше (lag>0) или позже (lag<0); длина сохраняется. */
    fun shift(a: PcmFile, lag: Int, out: File): PcmFile {
        if (lag >= 0) return PcmMath.advance(a, lag.toLong(), out)
        val ch = a.channels
        val total = a.frames
        val block = 8192
        val buf = Array(ch) { FloatArray(block) }
        var written = 0L
        PcmWriter(out, a.sampleRate, ch).use { w ->
            val zeros = minOf((-lag).toLong(), total)
            while (written < zeros) {
                val k = minOf(block.toLong(), zeros - written).toInt()
                w.write(buf, 0, k)
                written += k
            }
            PcmReader(a).use { r ->
                while (written < total) {
                    val k = r.read(buf, minOf(block.toLong(), total - written).toInt())
                    if (k <= 0) break
                    w.write(buf, 0, k)
                    written += k
                }
            }
        }
        return PcmFile(out, a.sampleRate, ch)
    }
}
