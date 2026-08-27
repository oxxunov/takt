package com.tupicgames.takt.core.io

import com.tupicgames.takt.core.model.Analysis
import com.tupicgames.takt.core.model.BeatInfo
import com.tupicgames.takt.core.model.Onset
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object AnalysisIo {

    private const val MAGIC = 0x52474131 // "RGA1"

    /**
     * Версия алгоритма анализа. Инкрементировать при любом изменении
     * analyzer.cpp — иначе после обновления приложения останутся старые кэши,
     * несовместимые с новой логикой генерации.
     */
    // Версия 4: переработан анализ ритма — многополосный поток, сплошной
    // перебор темпа, определение такта. Доли несут уверенность, силу и
    // положение в такте. Старые кэши несовместимы и пересчитаются молча.
    const val ALGO_VERSION = 4

    fun write(out: OutputStream, analysis: Analysis) {
        DataOutputStream(out.buffered()).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(ALGO_VERSION)
            d.writeFloat(analysis.bpm)
            d.writeFloat(analysis.firstBeatSec)
            d.writeFloat(analysis.durationSec)
            d.writeFloat(analysis.confidence)
            d.writeInt(analysis.meter)
            d.writeFloat(analysis.tempoSpread)
            d.writeInt(analysis.beatInfo.size)
            for (b in analysis.beatInfo) {
                d.writeFloat(b.timeSec)
                d.writeFloat(b.confidence)
                d.writeFloat(b.strength)
                d.writeInt(b.barIndex)
                d.writeInt(b.positionInBar)
            }
            d.writeFloat(analysis.envelopeHz)
            d.writeInt(analysis.envelope.size)
            for (e in analysis.envelope) d.writeFloat(e)
            d.writeInt(analysis.onsets.size)
            for (o in analysis.onsets) {
                d.writeFloat(o.timeSec)
                d.writeFloat(o.strength)
                d.writeFloat(o.low)
                d.writeFloat(o.mid)
                d.writeFloat(o.high)
                d.writeFloat(o.centroid)
                d.writeFloat(o.sustainSec)
            }
        }
    }

    /** @return null, если файл битый или собран другой версией алгоритма. */
    fun read(input: InputStream): Analysis? {
        return try {
            DataInputStream(input.buffered()).use { d ->
                if (d.readInt() != MAGIC) return null
                if (d.readInt() != ALGO_VERSION) return null
                val bpm = d.readFloat()
                val first = d.readFloat()
                val dur = d.readFloat()
                val conf = d.readFloat()
                val meter = d.readInt()
                val tempoSpread = d.readFloat()
                val beatCount = d.readInt()
                if (beatCount < 0 || beatCount > 500_000) return null
                val beatInfo = ArrayList<BeatInfo>(beatCount)
                repeat(beatCount) {
                    beatInfo.add(
                        BeatInfo(
                            d.readFloat(), d.readFloat(), d.readFloat(),
                            d.readInt(), d.readInt()
                        )
                    )
                }
                val beats = FloatArray(beatCount) { beatInfo[it].timeSec }
                val envHz = d.readFloat()
                val envCount = d.readInt()
                if (envCount < 0 || envCount > 5_000_000) return null
                val envelope = FloatArray(envCount) { d.readFloat() }
                val n = d.readInt()
                if (n < 0 || n > 2_000_000) return null
                val list = ArrayList<Onset>(n)
                repeat(n) {
                    list.add(
                        Onset(
                            d.readFloat(), d.readFloat(), d.readFloat(),
                            d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat()
                        )
                    )
                }
                Analysis(
                    bpm, first, dur, conf, list, beats, envelope, envHz,
                    beatInfo, meter, tempoSpread
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
