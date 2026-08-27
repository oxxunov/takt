package com.tupicgames.takt.engine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack

/**
 * Воспроизведение готового PCM из памяти.
 *
 * Здесь нет ни MediaCodec, ни MediaExtractor: файл уже разобран заранее.
 * Осталась одна подсистема вместо трёх, а значит втрое меньше мест, где
 * запуск уровня может сорваться.
 *
 * Часы: playbackHeadPosition считает кадры, реально ушедшие на выход, и
 * уточняется таймштампом, если устройство его отдаёт. Это точнее, чем
 * MediaPlayer.getCurrentPosition() с его шагом в 100 мс.
 */
class PcmPlayer(private val pcm: PcmDecoder.Pcm) : GameClock {

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var finished = false
    @Volatile private var started = false

    // Свой AudioTimestamp на поток: объект читают и игровой цикл,
    // и отрисовка, а общий даёт гонку и рваное время.
    private val timestampPerThread = object : ThreadLocal<AudioTimestamp>() {
        override fun initialValue() = AudioTimestamp()
    }

    @Volatile var failure: Throwable? = null
        private set

    val durationSec: Float get() = pcm.durationSec

    /** @return false, если устройство не дало создать выходной трек. */
    fun open(): Boolean {
        if (track != null) return true
        return try {
            track = build(lowLatency = true) ?: build(lowLatency = false)
            track != null
        } catch (e: Throwable) {
            failure = e
            false
        }
    }

    private fun build(lowLatency: Boolean): AudioTrack? {
        val channelMask = when (pcm.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> null
        }

        val formatBuilder = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(pcm.sampleRate)
        if (channelMask != null) formatBuilder.setChannelMask(channelMask)
        // Для 5.1 и выше готовой маски нет — берём индексную,
        // иначе кадры разъезжаются по каналам.
        else formatBuilder.setChannelIndexMask((1 shl pcm.channels) - 1)

        val legacyMask = if (pcm.channels == 1)
            AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(
            pcm.sampleRate, legacyMask, AudioFormat.ENCODING_PCM_16BIT
        )
        // getMinBufferSize отдаёт отрицательные коды ошибок; умножив их,
        // мы бы передали в setBufferSizeInBytes мусор.
        val bufferBytes = if (minBuf > 0) minBuf * 4 else pcm.sampleRate * pcm.channels

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(formatBuilder.build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .apply {
                    // LOW_LATENCY поддержан не везде и роняет build().
                    if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                .build()
        } catch (e: Throwable) {
            if (lowLatency) null else throw e
        }
    }

    fun start() {
        val t = track ?: return
        if (running) return
        running = true
        finished = false
        t.play()
        started = true

        thread = Thread({ pump(t) }, "pcm-pump").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun pump(t: AudioTrack) {
        val chunk = 4096
        var offset = 0
        try {
            while (running && offset < pcm.samples.size) {
                val n = minOf(chunk, pcm.samples.size - offset)
                // Блокирующая запись задаёт темп цикла.
                val written = t.write(pcm.samples, offset, n, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        } catch (e: Throwable) {
            failure = e
        }
        finished = true
    }

    override fun positionSec(): Double {
        val t = track ?: return 0.0
        if (!started) return 0.0
        return try {
            val head = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL).toDouble() / pcm.sampleRate
            val ts = timestampPerThread.get()
            if (ts != null && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.getTimestamp(ts)) {
                val elapsed = (System.nanoTime() - ts.nanoTime) / 1e9
                (ts.framePosition.toDouble() / pcm.sampleRate) + elapsed
            } else head
        } catch (e: Exception) {
            0.0
        }
    }

    override fun isRunning(): Boolean = started && !finished

    fun pause() { runCatching { track?.pause() } }

    fun release() {
        running = false
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        thread?.join(1000)
        thread = null
        runCatching { track?.stop() }
        track?.release()
        track = null
    }
}

/**
 * Запасные часы, когда звук вообще не удалось открыть.
 *
 * Уровень остаётся играбельным без звука — это лучше, чем экран,
 * который не открывается.
 */
class SystemClock2(private val durationSec: Float) : GameClock {
    private var startNanos = 0L
    private var running = false

    fun start() { startNanos = System.nanoTime(); running = true }

    override fun positionSec(): Double =
        if (!running) 0.0 else (System.nanoTime() - startNanos) / 1e9

    override fun isRunning(): Boolean = running && positionSec() < durationSec + 1.0
}
