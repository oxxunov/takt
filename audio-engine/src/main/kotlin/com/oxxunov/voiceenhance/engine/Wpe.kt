package com.oxxunov.voiceenhance.engine

import org.jtransforms.fft.DoubleFFT_1D
import java.io.File
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * De-Reverb методом WPE (Weighted Prediction Error, Nakatani/Yoshioka) — тот же алгоритм,
 * что в эталонной nara_wpe (MIT): в STFT-области поздняя реверберация предсказывается линейным
 * фильтром из прошлых кадров (с задержкой, чтобы не трогать прямой звук и ранние отражения)
 * и вычитается. Веса 1/|Y|² — итеративная оценка мощности чистой речи (3 итерации).
 *
 * Не «режет» спектр масками — поэтому не даёт металлического звука.
 * Файл обрабатывается блоками по ~20 с (память не растёт), каналы — независимо.
 *
 * @param strength 0..1 — доля вычитаемой поздней реверберации.
 */
class WpeDereverb(
    private val strength: Double,
    private val iterations: Int = 3,
    private val blockSeconds: Double = 20.0,
) : FileStage {

    override fun run(input: PcmFile, output: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): PcmFile {
        val ch = input.channels
        val total = input.frames
        val dir = output.absoluteFile.parentFile
        val monoOut = Array(ch) { File(dir, "wpe_ch${it}_${System.nanoTime()}.f32") }
        try {
            for (c in 0 until ch) {
                processChannel(input, c, monoOut[c], { p -> progress((c + p) / ch * 0.97f) }, isCancelled)
            }
            // склейка каналов
            val readers = monoOut.map { PcmReader(PcmFile(it, input.sampleRate, 1)) }
            try {
                PcmWriter(output, input.sampleRate, ch).use { w ->
                    val block = 8192
                    val one = Array(1) { FloatArray(block) }
                    val buf = Array(ch) { FloatArray(block) }
                    var done = 0L
                    while (done < total) {
                        val n = min(block.toLong(), total - done).toInt()
                        for (c in 0 until ch) {
                            val got = readers[c].read(one, n)
                            System.arraycopy(one[0], 0, buf[c], 0, got)
                            if (got < n) java.util.Arrays.fill(buf[c], got, n, 0f)
                        }
                        w.write(buf, 0, n)
                        done += n
                    }
                }
            } finally {
                readers.forEach { it.close() }
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            monoOut.forEach { it.delete() }
        }
        progress(1f)
        return PcmFile(output, input.sampleRate, ch)
    }

    private fun processChannel(
        input: PcmFile,
        channel: Int,
        out: File,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val total = input.frames
        val wpe = MonoWpe(input.sampleRate, strength, iterations, blockSeconds, isCancelled)
        var toSkip = wpe.padding.toLong()
        var written = 0L
        PcmWriter(out, input.sampleRate, 1).use { w ->
            val one = Array(1) { FloatArray(8192) }
            wpe.sink = { data, n ->
                var off = 0
                if (toSkip > 0) {
                    off = min(toSkip, n.toLong()).toInt()
                    toSkip -= off
                }
                val keep = min((n - off).toLong(), total - written).toInt()
                if (keep > 0) {
                    var k = 0
                    while (k < keep) {
                        val m = min(one[0].size, keep - k)
                        for (i in 0 until m) one[0][i] = data[off + k + i].toFloat()
                        w.write(one, 0, m)
                        k += m
                    }
                    written += keep
                }
            }
            PcmReader(input).use { r ->
                val block = 8192
                val buf = Array(input.channels) { FloatArray(block) }
                var done = 0L
                while (true) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    val n = r.read(buf, block)
                    if (n <= 0) break
                    wpe.push(buf[channel], n)
                    done += n
                    if (total > 0) progress((done.toDouble() / total).toFloat())
                }
            }
            wpe.finish()
            // на всякий случай добиваем до точной длины
            if (written < total) {
                java.util.Arrays.fill(one[0], 0f)
                while (written < total) {
                    val m = min(one[0].size.toLong(), total - written).toInt()
                    w.write(one, 0, m)
                    written += m
                }
            }
        }
    }

    companion object {
        /** Длина окна STFT: ~64–85 мс (как в эталонных настройках WPE, масштабировано по частоте). */
        fun fftSize(sampleRate: Int): Int = when {
            sampleRate >= 40000 -> 4096
            sampleRate >= 24000 -> 2048
            sampleRate >= 12000 -> 1024
            else -> 512
        }

        const val DELAY = 3
        const val TAPS = 20
    }
}

/** Потоковый одноканальный WPE: STFT (sqrt-Hann, 75 %) → блочная оценка фильтров → ISTFT. */
internal class MonoWpe(
    sampleRate: Int,
    private val strength: Double,
    private val iterations: Int,
    blockSeconds: Double,
    private val isCancelled: () -> Boolean,
) {
    private val n = WpeDereverb.fftSize(sampleRate)
    private val hop = n / 4
    private val bins = n / 2 + 1
    private val d = WpeDereverb.DELAY
    private val k = WpeDereverb.TAPS
    private val hist = k + d - 1
    private val blockFrames = max(4 * (k + d), (blockSeconds * sampleRate / hop).toInt())

    /** Сколько первых выходных сэмплов — это стартовая подкладка нулей. */
    val padding = n - hop

    var sink: (DoubleArray, Int) -> Unit = { _, _ -> }

    private val fft = DoubleFFT_1D(n.toLong())
    private val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / n)) }
    private val frameBuf = DoubleArray(n)
    private var pos = padding
    private val tmp = DoubleArray(n)
    private val ola = DoubleArray(n)

    // кадры текущего блока: re/im по bins
    private val blockRe = ArrayList<FloatArray>()
    private val blockIm = ArrayList<FloatArray>()
    // история исходных кадров для предсказания через границу блока (bin-major: [f*hist + j])
    private val histRe = FloatArray(bins * hist)
    private val histIm = FloatArray(bins * hist)

    fun push(x: FloatArray, count: Int) {
        for (i in 0 until count) {
            frameBuf[pos++] = x[i].toDouble()
            if (pos == n) {
                analyze()
                System.arraycopy(frameBuf, hop, frameBuf, 0, n - hop)
                pos = n - hop
            }
        }
    }

    fun finish() {
        // n нулей гарантируют, что все реальные сэмплы вошли в 4 кадра
        val z = FloatArray(n)
        push(z, n)
        if (blockRe.isNotEmpty()) processBlock()
    }

    private fun analyze() {
        for (i in 0 until n) tmp[i] = frameBuf[i] * win[i]
        fft.realForward(tmp)
        val re = FloatArray(bins)
        val im = FloatArray(bins)
        re[0] = tmp[0].toFloat()
        re[bins - 1] = tmp[1].toFloat()
        for (f in 1 until bins - 1) {
            re[f] = tmp[2 * f].toFloat()
            im[f] = tmp[2 * f + 1].toFloat()
        }
        blockRe.add(re)
        blockIm.add(im)
        if (blockRe.size == blockFrames) processBlock()
    }

    private fun processBlock() {
        if (isCancelled()) throw ProcessingCancelledException()
        val t = blockRe.size
        val tt = hist + t
        // bin-major копия: история + блок
        val xr = FloatArray(bins * tt)
        val xi = FloatArray(bins * tt)
        for (f in 0 until bins) {
            val base = f * tt
            System.arraycopy(histRe, f * hist, xr, base, hist)
            System.arraycopy(histIm, f * hist, xi, base, hist)
            for (j in 0 until t) {
                xr[base + hist + j] = blockRe[j][f]
                xi[base + hist + j] = blockIm[j][f]
            }
        }
        val yr = FloatArray(bins * t)
        val yi = FloatArray(bins * t)
        for (f in 0 until bins) {
            System.arraycopy(xr, f * tt + hist, yr, f * t, t)
            System.arraycopy(xi, f * tt + hist, yi, f * t, t)
        }

        val iters = max(1, iterations)
        for (it in 0 until iters) {
            if (isCancelled()) throw ProcessingCancelledException()
            var maxP = 0.0
            for (i in yr.indices) {
                val p = yr[i].toDouble() * yr[i] + yi[i].toDouble() * yi[i]
                if (p > maxP) maxP = p
            }
            val eps = max(1e-10 * maxP, 1e-30)
            val alpha = if (it == iters - 1) strength else 1.0
            IntStream.range(0, bins).parallel().forEach { f ->
                estimateAndApply(f, t, tt, xr, xi, yr, yi, eps, alpha)
            }
        }

        // ISTFT + overlap-add
        for (j in 0 until t) {
            tmp[0] = yr[j].toDouble()
            tmp[1] = yr[(bins - 1) * t + j].toDouble()
            for (f in 1 until bins - 1) {
                tmp[2 * f] = yr[f * t + j].toDouble()
                tmp[2 * f + 1] = yi[f * t + j].toDouble()
            }
            fft.realInverse(tmp, true)
            for (i in 0 until n) ola[i] += tmp[i] * win[i] * 0.5
            sink(ola, hop)
            System.arraycopy(ola, hop, ola, 0, n - hop)
            java.util.Arrays.fill(ola, n - hop, n, 0.0)
        }

        // новая история — последние hist исходных кадров
        for (f in 0 until bins) {
            System.arraycopy(xr, f * tt + tt - hist, histRe, f * hist, hist)
            System.arraycopy(xi, f * tt + tt - hist, histIm, f * hist, hist)
        }
        blockRe.clear()
        blockIm.clear()
    }

    /** Для одного частотного бина: R g = r (взвешенный МНК), затем Y = X − α·gᴴx̃. */
    private fun estimateAndApply(
        f: Int, t: Int, tt: Int,
        xr: FloatArray, xi: FloatArray,
        yr: FloatArray, yi: FloatArray,
        eps: Double, alpha: Double,
    ) {
        val xb = f * tt
        val yb = f * t
        val rr = DoubleArray(k * k)
        val ri = DoubleArray(k * k)
        val pr = DoubleArray(k)
        val pi = DoubleArray(k)
        for (j in 0 until t) {
            val py = yr[yb + j].toDouble() * yr[yb + j] + yi[yb + j].toDouble() * yi[yb + j]
            val w = 1.0 / max(py, eps)
            val cr = xr[xb + hist + j].toDouble()
            val ci = xi[xb + hist + j].toDouble()
            val c0 = xb + hist + j - d
            for (a in 0 until k) {
                val ar = xr[c0 - a].toDouble() * w
                val ai = xi[c0 - a].toDouble() * w
                // r_a += w·x_a·conj(X)
                pr[a] += ar * cr + ai * ci
                pi[a] += ai * cr - ar * ci
                val row = a * k
                for (b in a until k) {
                    val br = xr[c0 - b].toDouble()
                    val bi = xi[c0 - b].toDouble()
                    // R_ab += w·x_a·conj(x_b)
                    rr[row + b] += ar * br + ai * bi
                    ri[row + b] += ai * br - ar * bi
                }
            }
        }
        // эрмитова симметрия + диагональная регуляризация
        var tr = 0.0
        for (a in 0 until k) tr += rr[a * k + a]
        val load = 1e-8 * tr / k + 1e-30
        for (a in 0 until k) {
            rr[a * k + a] += load
            ri[a * k + a] = 0.0
            for (b in 0 until a) {
                rr[a * k + b] = rr[b * k + a]
                ri[a * k + b] = -ri[b * k + a]
            }
        }
        if (!solveComplex(rr, ri, pr, pi, k)) return // вырожденный бин — оставляем как есть

        for (j in 0 until t) {
            val c0 = xb + hist + j - d
            var er = 0.0
            var ei = 0.0
            for (a in 0 until k) {
                val gr = pr[a]
                val gi = pi[a]
                val vr = xr[c0 - a].toDouble()
                val vi = xi[c0 - a].toDouble()
                // conj(g)·x
                er += gr * vr + gi * vi
                ei += gr * vi - gi * vr
            }
            yr[yb + j] = (xr[xb + hist + j] - alpha * er).toFloat()
            yi[yb + j] = (xi[xb + hist + j] - alpha * ei).toFloat()
        }
    }
}

/** Решает комплексную систему A·x = b методом Гаусса с выбором ведущего элемента. Результат — в (br, bi). */
internal fun solveComplex(ar: DoubleArray, ai: DoubleArray, br: DoubleArray, bi: DoubleArray, n: Int): Boolean {
    for (col in 0 until n) {
        var piv = col
        var best = hypot(ar[col * n + col], ai[col * n + col])
        for (r in col + 1 until n) {
            val m = hypot(ar[r * n + col], ai[r * n + col])
            if (m > best) { best = m; piv = r }
        }
        if (best < 1e-300 || best.isNaN()) return false
        if (piv != col) {
            for (c in 0 until n) {
                var s = ar[col * n + c]; ar[col * n + c] = ar[piv * n + c]; ar[piv * n + c] = s
                s = ai[col * n + c]; ai[col * n + c] = ai[piv * n + c]; ai[piv * n + c] = s
            }
            var s = br[col]; br[col] = br[piv]; br[piv] = s
            s = bi[col]; bi[col] = bi[piv]; bi[piv] = s
        }
        val dr = ar[col * n + col]
        val di = ai[col * n + col]
        val den = dr * dr + di * di
        for (r in col + 1 until n) {
            val xr = ar[r * n + col]
            val xi = ai[r * n + col]
            // factor = A[r][col] / A[col][col]
            val fr = (xr * dr + xi * di) / den
            val fi = (xi * dr - xr * di) / den
            if (fr == 0.0 && fi == 0.0) continue
            for (c in col until n) {
                val cr = ar[col * n + c]
                val ci = ai[col * n + c]
                ar[r * n + c] -= fr * cr - fi * ci
                ai[r * n + c] -= fr * ci + fi * cr
            }
            br[r] -= fr * br[col] - fi * bi[col]
            bi[r] -= fr * bi[col] + fi * br[col]
        }
    }
    for (row in n - 1 downTo 0) {
        var sr = br[row]
        var si = bi[row]
        for (c in row + 1 until n) {
            val a1 = ar[row * n + c]
            val a2 = ai[row * n + c]
            sr -= a1 * br[c] - a2 * bi[c]
            si -= a1 * bi[c] + a2 * br[c]
        }
        val dr = ar[row * n + row]
        val di = ai[row * n + row]
        val den = dr * dr + di * di
        br[row] = (sr * dr + si * di) / den
        bi[row] = (si * dr - sr * di) / den
    }
    for (i in 0 until n) if (br[i].isNaN() || bi[i].isNaN()) return false
    return true
}
