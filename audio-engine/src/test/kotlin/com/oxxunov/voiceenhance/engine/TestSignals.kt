package com.oxxunov.voiceenhance.engine

import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object TestSignals {
    fun tempDir(): File = Files.createTempDirectory("ve-test").toFile().apply { deleteOnExit() }

    fun sine(sr: Int, freq: Double, amp: Double, seconds: Double, channels: Int = 1): Array<FloatArray> {
        val n = (sr * seconds).toInt()
        return Array(channels) { FloatArray(n) { i -> (amp * sin(2 * PI * freq * i / sr)).toFloat() } }
    }

    /** Речеподобный тест: сумма гармоник с амплитудной модуляцией (слоги ~4 Гц) + шум + сибилянты. */
    fun speechLike(sr: Int, seconds: Double, channels: Int, gain: Double, seed: Int = 7): Array<FloatArray> {
        val n = (sr * seconds).toInt()
        val rnd = Random(seed)
        val mono = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = 0.5 + 0.5 * sin(2 * PI * 4.0 * t)
            var v = 0.0
            for (h in 1..8) v += sin(2 * PI * 140.0 * h * t) / h
            v *= env
            val sib = if ((t % 1.0) in 0.40..0.52) (rnd.nextDouble() - 0.5) * 0.8 else 0.0
            v += sib + (rnd.nextDouble() - 0.5) * 0.02
            mono[i] = (v * gain).toFloat()
        }
        return Array(channels) { mono.copyOf() }
    }

    fun toPcm(dir: File, name: String, sr: Int, x: Array<FloatArray>): PcmFile {
        val w = PcmWriter(File(dir, name), sr, x.size)
        w.use { it.write(x, 0, x[0].size) }
        return PcmFile(File(dir, name), sr, x.size)
    }

    fun readAll(pcm: PcmFile): Array<FloatArray> {
        val n = pcm.frames.toInt()
        val out = Array(pcm.channels) { FloatArray(n) }
        PcmReader(pcm).use { it.read(out, n) }
        return out
    }

    fun runProc(p: AudioProcessor, sr: Int, x: Array<FloatArray>, block: Int = 1000): Array<FloatArray> {
        p.prepare(sr, x.size)
        val n = x[0].size
        val out = Array(x.size) { x[it].copyOf() }
        val tmp = Array(x.size) { FloatArray(block) }
        var i = 0
        while (i < n) {
            val m = minOf(block, n - i)
            for (c in x.indices) System.arraycopy(out[c], i, tmp[c], 0, m)
            p.process(tmp, m)
            for (c in x.indices) System.arraycopy(tmp[c], 0, out[c], i, m)
            i += m
        }
        return out
    }

    fun rmsDb(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return Db.fromLin(sqrt(s / (to - from)))
    }

    fun truePeakDb(x: Array<FloatArray>): Double {
        var tp = 0.0
        for (ch in x) {
            val d = TruePeakDetector()
            for (v in ch) tp = maxOf(tp, d.push(v.toDouble()))
            repeat(TruePeakKernel.DELAY) { tp = maxOf(tp, d.push(0.0)) }
        }
        return Db.fromLin(tp)
    }

    fun assertFinite(x: Array<FloatArray>) {
        for (ch in x) for (v in ch) check(!v.isNaN() && !v.isInfinite()) { "NaN/Inf в выходе" }
    }
}
