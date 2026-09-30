package com.oxxunov.voiceenhance.engine

import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Участок ручной правки: [startFrame, endFrame) и громкость каждого источника (1 = оставить, 0 = убрать),
 * в порядке списка стемов.
 */
data class MixRegion(val startFrame: Long, val endFrame: Long, val gains: FloatArray)

/**
 * Ручная правка результата: вне участков — готовый фон, внутри — смесь
 * «оригинал − Σ (1 − gₖ)·стемₖ» по выбранным громкостям. На границах — плавный переход 30 мс.
 * Если участки пересекаются, действует добавленный позже.
 */
object RegionMixer {
    private const val BLOCK = 8192

    fun render(
        original: PcmFile,
        base: PcmFile,
        stems: List<PcmFile>,
        regions: List<MixRegion>,
        out: File,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PcmFile {
        val ch = original.channels
        val sr = original.sampleRate
        require(base.channels == ch && stems.all { it.channels == ch && it.sampleRate == sr })
        val fade = max(1L, sr * 30L / 1000)
        val total = original.frames
        val ro = PcmReader(original)
        val rb = PcmReader(base)
        val rs = stems.map { PcmReader(it) }
        val bo = Array(ch) { FloatArray(BLOCK) }
        val bb = Array(ch) { FloatArray(BLOCK) }
        val bs = stems.map { Array(ch) { FloatArray(BLOCK) } }
        try {
            PcmWriter(out, sr, ch).use { w ->
                var pos = 0L
                while (pos < total) {
                    if (isCancelled()) throw ProcessingCancelledException()
                    val n = ro.read(bo, BLOCK)
                    if (n <= 0) break
                    val nb = rb.read(bb, n)
                    for (c in 0 until ch) if (nb < n) java.util.Arrays.fill(bb[c], max(nb, 0), n, 0f)
                    for ((k, r) in rs.withIndex()) {
                        val m = r.read(bs[k], n)
                        for (c in 0 until ch) if (m < n) java.util.Arrays.fill(bs[k][c], max(m, 0), n, 0f)
                    }
                    // участки, задевающие этот блок
                    val active = regions.filter { it.endFrame > pos && it.startFrame < pos + n }
                    for (i in 0 until n) {
                        val f = pos + i
                        var reg: MixRegion? = null
                        var wgt = 0.0
                        for (r in active) {
                            if (f < r.startFrame || f >= r.endFrame) continue
                            val len = r.endFrame - r.startFrame
                            val ramp = min(fade, len / 2).coerceAtLeast(1)
                            val a = min(f - r.startFrame + 1, r.endFrame - f).toDouble()
                            reg = r
                            wgt = min(1.0, a / ramp)
                        }
                        if (reg == null) {
                            for (c in 0 until ch) bo[c][i] = bb[c][i]
                        } else {
                            for (c in 0 until ch) {
                                var mix = bo[c][i].toDouble()
                                for (k in stems.indices) {
                                    val g = reg.gains.getOrElse(k) { 1f }
                                    mix -= (1.0 - g) * bs[k][c][i]
                                }
                                bo[c][i] = ((1 - wgt) * bb[c][i] + wgt * mix).toFloat()
                            }
                        }
                    }
                    w.write(bo, 0, n)
                    pos += n
                    if (total > 0) progress((pos.toDouble() / total).toFloat())
                }
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            ro.close(); rb.close(); rs.forEach { it.close() }
        }
        return PcmFile(out, sr, ch)
    }
}
