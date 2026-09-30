package com.oxxunov.voiceenhance.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class AudioAnalysis(
    val durationSec: Double,
    val sampleRate: Int,
    val channels: Int,
    val peakDb: Double,
    val truePeakDb: Double,
    val rmsDb: Double,
    val lufs: Double,
    /** Оценка шумового фона: 10-й перцентиль RMS по окнам 50 мс. */
    val noiseFloorDb: Double,
    /** Оценка уровня речи: 90-й перцентиль RMS по окнам 50 мс. */
    val speechLevelDb: Double,
    val snrDb: Double,
    /** Сэмплы в сериях ≥ 3 подряд на уровне ≥ −0.01 dBFS — признак клиппинга. */
    val clippedSamples: Long,
)

class ProcessingCancelledException : RuntimeException("Отменено")

object AudioAnalyzer {
    private const val BLOCK = 8192
    private const val CLIP_LEVEL = 0.9989 // ≈ −0.01 dBFS

    fun analyze(
        pcm: PcmFile,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AudioAnalysis {
        val ch = pcm.channels
        val sr = pcm.sampleRate
        val total = pcm.frames
        val buf = Array(ch) { FloatArray(BLOCK) }
        val meter = LoudnessMeter(sr, ch)
        val tpd = Array(ch) { TruePeakDetector() }
        val clipRun = IntArray(ch)
        var clipped = 0L
        var peak = 0.0
        var tp = 0.0
        var sumSq = 0.0
        var count = 0L

        val frameLen = max(1, (sr * 0.05).roundToInt())
        var frameAcc = 0.0
        var frameN = 0
        var frameDb = FloatArray(4096)
        var nFrames = 0

        PcmReader(pcm).use { reader ->
            var done = 0L
            while (true) {
                if (isCancelled()) throw ProcessingCancelledException()
                val n = reader.read(buf, BLOCK)
                if (n <= 0) break
                meter.process(buf, 0, n)
                for (i in 0 until n) {
                    var mono = 0.0
                    for (c in 0 until ch) {
                        val v = buf[c][i].toDouble()
                        val a = abs(v)
                        if (a > peak) peak = a
                        val p = tpd[c].push(v)
                        if (p > tp) tp = p
                        sumSq += v * v
                        mono += v
                        if (a >= CLIP_LEVEL) {
                            clipRun[c]++
                            if (clipRun[c] == 3) clipped += 3 else if (clipRun[c] > 3) clipped++
                        } else {
                            clipRun[c] = 0
                        }
                    }
                    mono /= ch
                    frameAcc += mono * mono
                    frameN++
                    if (frameN == frameLen) {
                        if (nFrames == frameDb.size) frameDb = frameDb.copyOf(frameDb.size * 2)
                        frameDb[nFrames++] = Db.fromPower(frameAcc / frameLen).coerceAtLeast(-120.0).toFloat()
                        frameAcc = 0.0
                        frameN = 0
                    }
                }
                count += n.toLong() * ch
                done += n
                if (total > 0) progress((done.toDouble() / total).toFloat())
            }
        }
        // дочитываем хвост интерполятора true peak
        for (c in 0 until ch) repeat(TruePeakKernel.DELAY) {
            val p = tpd[c].push(0.0)
            if (p > tp) tp = p
        }

        val sorted = frameDb.copyOf(nFrames).also { it.sort() }
        fun pct(q: Double): Double =
            if (nFrames == 0) -120.0 else sorted[((nFrames - 1) * q).roundToInt()].toDouble()
        val noise = pct(0.10)
        val speech = pct(0.90)
        return AudioAnalysis(
            durationSec = pcm.durationSeconds,
            sampleRate = sr,
            channels = ch,
            peakDb = Db.fromLin(peak),
            truePeakDb = Db.fromLin(tp),
            rmsDb = if (count > 0) Db.fromPower(sumSq / count) else -240.0,
            lufs = meter.integratedLufs(),
            noiseFloorDb = noise,
            speechLevelDb = speech,
            snrDb = speech - noise,
            clippedSamples = clipped,
        )
    }
}
