package com.oxxunov.voiceenhance.engine

import org.jtransforms.fft.DoubleFFT_1D
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Сила удаления речи. */
enum class RemovalStrength {
    /** Остаётся лёгкий след речи — самый естественный фон. */
    LOW,
    /** Вычитается ровно то, что модель посчитала речью. */
    MEDIUM,
    /** Плюс подавление остатков речи в частотно-временных точках, где она преобладает (риск задеть музыку). */
    HIGH,
}

/**
 * Финальное удаление речи: фон = смесь − речь, с разной силой.
 * HIGH: после вычитания в STFT-области применяется винеровское усиление G = |B|²/(|B|²+|S|²)
 * (сглаженное по времени, не ниже −20 дБ) — подавляет «протечки» речи там, где её энергия выше фона.
 */
object SpeechRemoval {
    private const val BLOCK = 8192
    private const val FLOOR = 0.1
    private const val SMOOTH = 0.5

    fun apply(
        mix: PcmFile,
        speech: PcmFile,
        strength: RemovalStrength,
        out: File,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PcmFile = when (strength) {
        RemovalStrength.LOW -> PcmMath.subtract(mix, speech, 0.8f, out, isCancelled).also { progress(1f) }
        RemovalStrength.MEDIUM -> PcmMath.subtract(mix, speech, 1.0f, out, isCancelled).also { progress(1f) }
        RemovalStrength.HIGH -> wiener(mix, speech, out, progress, isCancelled)
    }

    private fun wiener(mix: PcmFile, speech: PcmFile, out: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): PcmFile {
        require(mix.sampleRate == speech.sampleRate && mix.channels == speech.channels)
        val ch = mix.channels
        val sr = mix.sampleRate
        val n = 1 shl (ln(0.043 * sr) / ln(2.0)).roundToInt()
        val hop = n / 4
        val bins = n / 2 + 1
        val pad = n - hop
        val fft = DoubleFFT_1D(n.toLong())
        val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / n)) }
        val total = mix.frames

        val fx = Array(ch) { DoubleArray(n) }
        val fs = Array(ch) { DoubleArray(n) }
        val ola = Array(ch) { DoubleArray(n) }
        val gPrev = Array(ch) { DoubleArray(bins) { 1.0 } }
        val tx = DoubleArray(n)
        val ts = DoubleArray(n)
        var pos = pad
        var toSkip = pad.toLong()
        var written = 0L
        val outBuf = Array(ch) { FloatArray(hop) }

        PcmWriter(out, sr, ch).use { w ->
            fun frame() {
                for (c in 0 until ch) {
                    for (i in 0 until n) { tx[i] = fx[c][i] * win[i]; ts[i] = fs[c][i] * win[i] }
                    fft.realForward(tx)
                    fft.realForward(ts)
                    val gp = gPrev[c]
                    for (b in 0 until bins) {
                        val (ir, ii) = when (b) {
                            0 -> 0 to -1
                            bins - 1 -> 1 to -1
                            else -> 2 * b to 2 * b + 1
                        }
                        val xr = tx[ir]; val xi = if (ii >= 0) tx[ii] else 0.0
                        val sr0 = ts[ir]; val si = if (ii >= 0) ts[ii] else 0.0
                        val br = xr - sr0
                        val bi = xi - si
                        val pb = br * br + bi * bi
                        val ps = sr0 * sr0 + si * si
                        var g = if (pb + ps > 1e-20) pb / (pb + ps) else 1.0
                        g = max(FLOOR, g)
                        g = SMOOTH * gp[b] + (1 - SMOOTH) * g
                        gp[b] = g
                        tx[ir] = br * g
                        if (ii >= 0) tx[ii] = bi * g
                    }
                    fft.realInverse(tx, true)
                    val o = ola[c]
                    for (i in 0 until n) o[i] += tx[i] * win[i] * 0.5
                    for (i in 0 until hop) outBuf[c][i] = o[i].toFloat()
                    System.arraycopy(o, hop, o, 0, n - hop)
                    java.util.Arrays.fill(o, n - hop, n, 0.0)
                    System.arraycopy(fx[c], hop, fx[c], 0, n - hop)
                    System.arraycopy(fs[c], hop, fs[c], 0, n - hop)
                }
                var off = 0
                if (toSkip > 0) { off = min(toSkip, hop.toLong()).toInt(); toSkip -= off }
                val keep = min((hop - off).toLong(), total - written).toInt()
                if (keep > 0) {
                    if (off == 0) w.write(outBuf, 0, keep)
                    else w.write(outBuf, off, keep)
                    written += keep
                }
                pos = n - hop
            }

            val bx = Array(ch) { FloatArray(BLOCK) }
            val bs = Array(ch) { FloatArray(BLOCK) }
            PcmReader(mix).use { rx ->
                PcmReader(speech).use { rs ->
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        val m = rx.read(bx, BLOCK)
                        if (m <= 0) break
                        val ms = rs.read(bs, m)
                        for (i in 0 until m) {
                            for (c in 0 until ch) {
                                fx[c][pos] = bx[c][i].toDouble()
                                fs[c][pos] = if (i < ms) bs[c][i].toDouble() else 0.0
                            }
                            pos++
                            if (pos == n) frame()
                        }
                        done += m
                        if (total > 0) progress((done.toDouble() / total).toFloat())
                    }
                }
            }
            // досчитываем хвост нулями
            for (k in 0 until n) {
                for (c in 0 until ch) { fx[c][pos] = 0.0; fs[c][pos] = 0.0 }
                pos++
                if (pos == n) frame()
            }
            if (written < total) {
                val z = Array(ch) { FloatArray(BLOCK) }
                while (written < total) {
                    val m = min(BLOCK.toLong(), total - written).toInt()
                    w.write(z, 0, m)
                    written += m
                }
            }
        }
        progress(1f)
        return PcmFile(out, sr, ch)
    }
}
