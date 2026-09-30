package com.oxxunov.voiceenhance.ml

import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import com.oxxunov.voiceenhance.engine.PcmWriter
import com.oxxunov.voiceenhance.engine.ProcessingCancelledException
import java.io.File
import java.io.IOException
import kotlin.math.roundToLong

/** Пересчёт частоты дискретизации файла целиком (libsamplerate, наилучшее качество). Длина — точная. */
object Resampler {
    @JvmStatic private external fun nativeCreate(channels: Int, ratio: Double): Long
    @JvmStatic private external fun nativeProcess(h: Long, input: FloatArray?, frames: Int, eoi: Boolean): FloatArray?
    @JvmStatic private external fun nativeDestroy(h: Long)

    fun file(
        input: PcmFile,
        targetRate: Int,
        out: File,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        targetFrames: Long = -1,
    ): PcmFile {
        DeepFilterNative.loadError?.let { throw IOException("Ресемплер недоступен: ${it.message}") }
        val ch = input.channels
        val ratio = targetRate.toDouble() / input.sampleRate
        val wantFrames = if (targetFrames >= 0) targetFrames else (input.frames * ratio).roundToLong()
        val h = nativeCreate(ch, ratio)
        if (h == 0L) throw IOException("Не удалось создать ресемплер")
        var written = 0L
        try {
            PcmWriter(out, targetRate, ch).use { w ->
                fun emit(y: FloatArray?) {
                    if (y == null) throw IOException("Ошибка ресемплера")
                    val frames = y.size / ch
                    val keep = minOf(frames.toLong(), wantFrames - written).toInt()
                    if (keep > 0) {
                        w.writeInterleaved(y, keep * ch)
                        written += keep
                    }
                }
                val block = 8192
                val buf = Array(ch) { FloatArray(block) }
                val inter = FloatArray(block * ch)
                val total = input.frames
                var done = 0L
                PcmReader(input).use { r ->
                    while (true) {
                        if (isCancelled()) throw ProcessingCancelledException()
                        val n = r.read(buf, block)
                        if (n <= 0) break
                        var k = 0
                        for (i in 0 until n) for (c in 0 until ch) inter[k++] = buf[c][i]
                        emit(nativeProcess(h, inter, n, false))
                        done += n
                        if (total > 0) progress((done.toDouble() / total).toFloat())
                    }
                }
                emit(nativeProcess(h, null, 0, true))
                if (written < wantFrames) {
                    val z = FloatArray(block * ch)
                    while (written < wantFrames) {
                        val m = minOf(block.toLong(), wantFrames - written).toInt()
                        w.writeInterleaved(z, m * ch)
                        written += m
                    }
                }
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            nativeDestroy(h)
        }
        return PcmFile(out, targetRate, ch)
    }
}
