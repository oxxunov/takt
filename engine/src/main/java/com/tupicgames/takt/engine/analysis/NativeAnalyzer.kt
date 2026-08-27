package com.tupicgames.takt.engine.analysis

import com.tupicgames.takt.core.model.Analysis
import com.tupicgames.takt.core.model.BeatInfo
import com.tupicgames.takt.core.model.Onset

/**
 * Обвязка нативного анализатора.
 *
 * Имена методов привязаны через RegisterNatives в JNI_OnLoad: при
 * переименовании пакета библиотека не загрузится вообще, вместо тихого
 * UnsatisfiedLinkError посреди игры.
 */
object NativeAnalyzer {

    init { NativeLibrary.ensureLoaded() }

    private const val HEADER = 10
    private const val FIELDS = 7
    private const val BEAT_FIELDS = 5

    /**
     * @param minOnsetGap минимальный интервал между ударами, сек (0 = дефолт)
     * @param thresholdK  чувствительность порога (0 = дефолт)
     */
    fun analyze(
        samples: FloatArray,
        sampleRate: Int,
        minOnsetGap: Float = 0f,
        thresholdK: Float = 0f
    ): Analysis {
        val flat = nativeAnalyze(samples, sampleRate, minOnsetGap, thresholdK)
        if (flat.size < HEADER) return Analysis(0f, 0f, 0f, 0f, emptyList())

        val count = flat[4].toInt().coerceAtLeast(0)
        val beatCount = flat[5].toInt().coerceAtLeast(0)
        val envCount = flat[6].toInt().coerceAtLeast(0)
        val envHz = flat[7]
        val meter = flat[8].toInt()
        val tempoSpread = flat[9]

        val onsets = ArrayList<Onset>(count)
        for (i in 0 until count) {
            val b = HEADER + i * FIELDS
            if (b + FIELDS > flat.size) break
            onsets.add(
                Onset(
                    flat[b], flat[b + 1], flat[b + 2], flat[b + 3],
                    flat[b + 4], flat[b + 5], flat[b + 6]
                )
            )
        }

        val beatBase = HEADER + count * FIELDS
        val beatsFit = beatBase + beatCount * BEAT_FIELDS <= flat.size
        val realBeatCount = if (beatsFit) beatCount else 0

        val beats = FloatArray(realBeatCount) { flat[beatBase + it * BEAT_FIELDS] }
        val beatInfo = ArrayList<BeatInfo>(realBeatCount)
        for (i in 0 until realBeatCount) {
            val b = beatBase + i * BEAT_FIELDS
            beatInfo.add(
                BeatInfo(
                    timeSec = flat[b],
                    confidence = flat[b + 1],
                    strength = flat[b + 2],
                    barIndex = flat[b + 3].toInt(),
                    positionInBar = flat[b + 4].toInt()
                )
            )
        }

        val envBase = beatBase + realBeatCount * BEAT_FIELDS
        val envelope = FloatArray(
            if (envBase + envCount <= flat.size) envCount else 0
        ) { flat[envBase + it] }

        return Analysis(
            bpm = flat[0], firstBeatSec = flat[1], durationSec = flat[2],
            confidence = flat[3], onsets = onsets, beats = beats,
            envelope = envelope, envelopeHz = envHz,
            beatInfo = beatInfo, meter = meter, tempoSpread = tempoSpread
        )
    }

    private external fun nativeAnalyze(
        samples: FloatArray, sampleRate: Int, minOnsetGap: Float, thresholdK: Float
    ): FloatArray
}
