package com.oxxunov.voiceenhance.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

enum class WavFormat(val label: String, val bits: Int, val isFloat: Boolean) {
    PCM16("16-bit", 16, false),
    PCM24("24-bit", 24, false),
    FLOAT32("32-bit float", 32, true),
}

object WavWriter {
    private const val BLOCK = 8192

    /** Пишет WAV. 16-bit — с TPDF-дизерингом, 24-bit и float — без потерь относительно внутреннего формата. */
    fun write(
        pcm: PcmFile,
        out: OutputStream,
        format: WavFormat,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        val ch = pcm.channels
        val bps = format.bits / 8
        val frames = pcm.frames
        val dataLen = frames * ch * bps
        val pad = (dataLen and 1L).toInt()
        if (dataLen + pad + 36 > 0xFFFFFFFFL) throw IOException("Файл больше 4 ГБ — WAV такой размер не поддерживает")

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataLen + pad).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort((if (format.isFloat) 3 else 1).toShort())
        header.putShort(ch.toShort())
        header.putInt(pcm.sampleRate)
        header.putInt(pcm.sampleRate * ch * bps)
        header.putShort((ch * bps).toShort())
        header.putShort(format.bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen.toInt())

        val bos = BufferedOutputStream(out, 1 shl 16)
        bos.write(header.array())

        val buf = Array(ch) { FloatArray(BLOCK) }
        val bytes = ByteArray(BLOCK * ch * bps)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var rng = 0x2545F4914F6CDD1DL
        fun nextUniform(): Double {
            rng = rng xor (rng shl 13); rng = rng xor (rng ushr 7); rng = rng xor (rng shl 17)
            return (rng ushr 11).toDouble() / (1L shl 53).toDouble()
        }

        PcmReader(pcm).use { reader ->
            var done = 0L
            while (true) {
                if (isCancelled()) throw ProcessingCancelledException()
                val n = reader.read(buf, BLOCK)
                if (n <= 0) break
                var idx = 0
                for (i in 0 until n) {
                    for (c in 0 until ch) {
                        var v = buf[c][i].toDouble()
                        if (v.isNaN() || v.isInfinite()) v = 0.0
                        when (format) {
                            WavFormat.PCM16 -> {
                                val d = nextUniform() - nextUniform() // TPDF, ±1 LSB
                                val s = (v.coerceIn(-1.0, 1.0) * 32767.0 + d).roundToInt().coerceIn(-32768, 32767)
                                bb.putShort(idx, s.toShort())
                                idx += 2
                            }
                            WavFormat.PCM24 -> {
                                val s = (v.coerceIn(-1.0, 1.0) * 8388607.0).roundToInt().coerceIn(-8388608, 8388607)
                                bytes[idx] = (s and 0xFF).toByte()
                                bytes[idx + 1] = ((s shr 8) and 0xFF).toByte()
                                bytes[idx + 2] = ((s shr 16) and 0xFF).toByte()
                                idx += 3
                            }
                            WavFormat.FLOAT32 -> {
                                bb.putFloat(idx, v.toFloat())
                                idx += 4
                            }
                        }
                    }
                }
                bos.write(bytes, 0, idx)
                done += n
                if (frames > 0) progress((done.toDouble() / frames).toFloat())
            }
        }
        if (pad == 1) bos.write(0)
        bos.flush()
    }
}

object WavReader {
    private const val BLOCK_FRAMES = 8192

    fun isWav(head: ByteArray): Boolean =
        head.size >= 12 &&
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"

    /**
     * Читает WAV (PCM 8/16/24/32, float 32/64, WAVE_FORMAT_EXTENSIBLE) во внутренний float-формат.
     * Больше двух каналов сводятся в моно.
     */
    fun read(
        input: InputStream,
        outFile: File,
        totalBytesHint: Long = -1,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PcmFile {
        val ins = BufferedInputStream(input, 1 shl 16)
        val riff = readN(ins, 12)
        if (riff.size < 12 || !isWav(riff)) throw IOException("Это не WAV-файл")

        var tag = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataSize = -1L

        while (true) {
            val hdr = readN(ins, 8)
            if (hdr.size < 8) throw IOException("В WAV нет блока data")
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val f = readN(ins, size.toInt())
                val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                tag = b.getShort(0).toInt() and 0xFFFF
                channels = b.getShort(2).toInt() and 0xFFFF
                sampleRate = b.getInt(4)
                bits = b.getShort(14).toInt() and 0xFFFF
                if (tag == 0xFFFE && f.size >= 26) tag = b.getShort(24).toInt() and 0xFFFF
                if ((size and 1L) == 1L) skip(ins, 1)
            } else if (id == "data") {
                dataSize = size
                break
            } else {
                skip(ins, size + (size and 1L))
            }
        }
        if (tag < 0) throw IOException("В WAV нет блока fmt")
        val isPcm = tag == 1 && bits in intArrayOf(8, 16, 24, 32)
        val isFloat = tag == 3 && (bits == 32 || bits == 64)
        if (!isPcm && !isFloat) throw IOException("Неподдерживаемый формат WAV (tag=$tag, bits=$bits)")
        if (channels < 1 || sampleRate < 8000) throw IOException("Некорректный заголовок WAV")

        val bps = bits / 8
        val frameBytes = bps * channels
        val outCh = if (channels <= 2) channels else 1
        val limit = if (dataSize <= 0L || dataSize == 0xFFFFFFFFL) Long.MAX_VALUE else dataSize
        val writer = PcmWriter(outFile, sampleRate, outCh)
        val raw = ByteArray(BLOCK_FRAMES * frameBytes)
        val outBuf = FloatArray(BLOCK_FRAMES * outCh)
        var consumed = 0L
        try {
            while (consumed < limit) {
                if (isCancelled()) throw ProcessingCancelledException()
                val want = minOf(raw.size.toLong(), limit - consumed).toInt()
                val got = readInto(ins, raw, want)
                val frames = got / frameBytes
                if (frames == 0) break
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until frames) {
                    var mix = 0.0f
                    for (c in 0 until channels) {
                        val o = i * frameBytes + c * bps
                        val v: Float = when {
                            isFloat && bits == 32 -> bb.getFloat(o)
                            isFloat -> bb.getDouble(o).toFloat()
                            bits == 8 -> ((raw[o].toInt() and 0xFF) - 128) / 128f
                            bits == 16 -> bb.getShort(o) / 32768f
                            bits == 24 -> ((raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() shl 16)) / 8388608f
                            else -> (bb.getInt(o) / 2147483648.0).toFloat()
                        }
                        if (outCh == channels) outBuf[i * outCh + c] = v else mix += v
                    }
                    if (outCh != channels) outBuf[i] = mix / channels
                }
                writer.writeInterleaved(outBuf, frames * outCh)
                consumed += got
                if (totalBytesHint > 0) progress((consumed.toDouble() / totalBytesHint).toFloat().coerceAtMost(1f))
                if (got < want) break
            }
        } catch (e: Throwable) {
            writer.close()
            outFile.delete()
            throw e
        }
        return writer.finish()
    }

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        val got = readInto(ins, b, n)
        return if (got == n) b else b.copyOf(got)
    }

    private fun readInto(ins: InputStream, b: ByteArray, n: Int): Int {
        var got = 0
        while (got < n) {
            val r = ins.read(b, got, n - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    private fun skip(ins: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = ins.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
    }
}
