package com.oxxunov.voiceenhance.engine

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Промежуточное хранилище звука: 32-bit float, little-endian, interleaved, без заголовка.
 * Всё живёт на диске — в RAM держим только текущий блок, поэтому длина файла не ограничена памятью.
 */
class PcmFile(val file: File, val sampleRate: Int, val channels: Int) {
    val frames: Long get() = file.length() / (4L * channels)
    val durationSeconds: Double get() = frames.toDouble() / sampleRate
}

class PcmReader(val pcm: PcmFile) : Closeable {
    private val raf = RandomAccessFile(pcm.file, "r")
    private var bytes = ByteArray(0)

    var position: Long = 0
        private set

    fun seek(frame: Long) {
        val f = frame.coerceIn(0L, pcm.frames)
        raf.seek(f * 4L * pcm.channels)
        position = f
    }

    /** Читает до [maxFrames] кадров в планарный буфер [dst]. Возвращает число прочитанных кадров (0 = конец). */
    fun read(dst: Array<FloatArray>, maxFrames: Int): Int {
        val ch = pcm.channels
        val need = maxFrames * ch * 4
        if (bytes.size < need) bytes = ByteArray(need)
        var got = 0
        while (got < need) {
            val r = raf.read(bytes, got, need - got)
            if (r <= 0) break
            got += r
        }
        val frames = got / (4 * ch)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var idx = 0
        for (i in 0 until frames) {
            for (c in 0 until ch) {
                dst[c][i] = bb.getFloat(idx)
                idx += 4
            }
        }
        position += frames
        return frames
    }

    override fun close() = raf.close()
}

class PcmWriter(private val file: File, val sampleRate: Int, val channels: Int) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var bytes = ByteArray(0)
    private var closed = false

    var framesWritten: Long = 0
        private set

    private fun ensure(n: Int) {
        if (bytes.size < n) bytes = ByteArray(n)
    }

    /** Пишет [frames] кадров из планарного буфера, начиная с кадра [offset]. NaN/Inf заменяются нулём. */
    fun write(src: Array<FloatArray>, offset: Int, frames: Int) {
        if (frames <= 0) return
        val need = frames * channels * 4
        ensure(need)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var idx = 0
        for (i in offset until offset + frames) {
            for (c in 0 until channels) {
                bb.putFloat(idx, sanitize(src[c][i]))
                idx += 4
            }
        }
        out.write(bytes, 0, need)
        framesWritten += frames
    }

    /** Пишет [samples] interleaved-сэмплов (кратно числу каналов). */
    fun writeInterleaved(src: FloatArray, samples: Int) {
        val frames = samples / channels
        if (frames <= 0) return
        val n = frames * channels
        val need = n * 4
        ensure(need)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putFloat(i * 4, sanitize(src[i]))
        out.write(bytes, 0, need)
        framesWritten += frames
    }

    fun finish(): PcmFile {
        close()
        return PcmFile(file, sampleRate, channels)
    }

    override fun close() {
        if (closed) return
        closed = true
        out.close()
    }

    private fun sanitize(v: Float): Float = if (v.isNaN() || v.isInfinite()) 0f else v
}
