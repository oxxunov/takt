package com.oxxunov.voiceenhance.engine

import java.io.File

object PcmMath {
    private const val BLOCK = 8192

    /** out = a − gain·b (поэлементно, одинаковые формат и длина). Используется для «всё, кроме речи». */
    fun subtract(a: PcmFile, b: PcmFile, gain: Float, out: File, isCancelled: () -> Boolean = { false }): PcmFile {
        require(a.sampleRate == b.sampleRate && a.channels == b.channels) { "Разный формат дорожек" }
        val ch = a.channels
        val ba = Array(ch) { FloatArray(BLOCK) }
        val bb = Array(ch) { FloatArray(BLOCK) }
        PcmReader(a).use { ra ->
            PcmReader(b).use { rb ->
                PcmWriter(out, a.sampleRate, ch).use { w ->
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        val n = ra.read(ba, BLOCK)
                        if (n <= 0) break
                        val m = rb.read(bb, n)
                        for (c in 0 until ch) {
                            val x = ba[c]
                            val y = bb[c]
                            for (i in 0 until n) x[i] -= gain * (if (i < m) y[i] else 0f)
                        }
                        w.write(ba, 0, n)
                    }
                }
            }
        }
        return PcmFile(out, a.sampleRate, ch)
    }

    /** Сдвигает звук раньше на [frames] кадров (хвост добивается тишиной) — компенсация задержки кодера. */
    fun advance(a: PcmFile, frames: Long, out: File): PcmFile {
        val ch = a.channels
        val buf = Array(ch) { FloatArray(BLOCK) }
        val total = a.frames
        var written = 0L
        PcmReader(a).use { r ->
            r.seek(frames.coerceIn(0, total))
            PcmWriter(out, a.sampleRate, ch).use { w ->
                while (true) {
                    val n = r.read(buf, BLOCK)
                    if (n <= 0) break
                    w.write(buf, 0, n)
                    written += n
                }
                for (c in 0 until ch) java.util.Arrays.fill(buf[c], 0f)
                while (written < total) {
                    val n = minOf(BLOCK.toLong(), total - written).toInt()
                    w.write(buf, 0, n)
                    written += n
                }
            }
        }
        return PcmFile(out, a.sampleRate, ch)
    }
}

/** Какой контейнер можно записать без перекодирования видео (Android MediaMuxer). */
object ContainerRules {
    enum class Container(val ext: String, val mime: String) { MP4("mp4", "video/mp4"), WEBM("webm", "video/webm") }

    class Plan(val container: Container, val audioMime: String)

    /** null — этот видеокодек нельзя сохранить без перекодирования на данной версии Android. */
    fun plan(videoMime: String, sdkInt: Int): Plan? = when (videoMime) {
        "video/avc", "video/hevc", "video/mp4v-es", "video/3gpp" -> Plan(Container.MP4, "audio/mp4a-latm")
        "video/av01" -> if (sdkInt >= 34) Plan(Container.MP4, "audio/mp4a-latm") else null
        "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9" -> if (sdkInt >= 29) Plan(Container.WEBM, "audio/opus") else null
        else -> null
    }

    /** Частоты, которые принимает кодер Opus. */
    val OPUS_RATES = setOf(8000, 12000, 16000, 24000, 48000)
}
