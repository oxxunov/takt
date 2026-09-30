package com.oxxunov.voiceenhance.audioio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * Потоковое воспроизведение float-файла через AudioTrack (float, без потерь).
 * Смена источника на лету сохраняет позицию — для сравнения Оригинал/Обработка.
 */
class AudioPlayer {
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile private var source: PcmFile? = null
    @Volatile private var sourceChanged = false
    @Volatile private var seekTo = -1L
    @Volatile private var running = false
    @Volatile private var loopStart = -1L
    @Volatile private var loopEnd = -1L
    private var thread: Thread? = null

    fun setSource(pcm: PcmFile?, keepPosition: Boolean) {
        if (pcm == null) {
            pause()
            source = null
            _position.value = 0
            return
        }
        if (!keepPosition) seek(0)
        source = pcm
        sourceChanged = true
    }

    fun play() {
        if (running || source == null) return
        running = true
        _playing.value = true
        thread = Thread({ loop() }, "player").also { it.start() }
    }

    fun pause() {
        running = false
        thread?.join(1500)
        thread = null
        _playing.value = false
    }

    fun seek(frame: Long) {
        _position.value = max(0L, frame)
        seekTo = max(0L, frame)
    }

    fun release() = pause()

    /** Повтор участка [start, end) — для A/B-сравнения одного места. */
    fun setLoop(start: Long, end: Long) {
        loopStart = start
        loopEnd = end
        val p = _position.value
        if (p < start || p >= end) seek(start)
    }

    fun clearLoop() {
        loopStart = -1L
        loopEnd = -1L
    }

    private fun createTrack(sr: Int, ch: Int): AudioTrack {
        val mask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(max(min, sr * ch * 4 / 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun loop() {
        val block = 2048
        val planar = Array(2) { FloatArray(block) }
        val inter = FloatArray(block * 2)
        var track: AudioTrack? = null
        var trackSr = 0
        var trackCh = 0
        var reader: PcmReader? = null
        var pos = _position.value
        seekTo = -1L
        try {
            while (running) {
                val src = source ?: break
                val rd: PcmReader = if (reader == null || sourceChanged) {
                    sourceChanged = false
                    reader?.close()
                    PcmReader(src).also {
                        it.seek(pos)
                        reader = it
                    }
                } else reader!!
                val sk = seekTo
                if (sk >= 0) {
                    seekTo = -1L
                    pos = sk
                    rd.seek(pos)
                    track?.pause(); track?.flush(); track?.play()
                }
                val tr: AudioTrack = if (track == null || trackSr != src.sampleRate || trackCh != src.channels) {
                    track?.release()
                    createTrack(src.sampleRate, src.channels).also {
                        it.play()
                        track = it
                    }
                } else track!!
                trackSr = src.sampleRate
                trackCh = src.channels
                val ls = loopStart
                val le = loopEnd
                if (ls >= 0 && le > ls && pos >= le) {
                    pos = ls
                    rd.seek(pos)
                }
                val want = if (ls >= 0 && le > ls) minOf(block.toLong(), le - pos).toInt().coerceAtLeast(1) else block
                val n = rd.read(planar, want)
                if (n <= 0) {
                    _position.value = 0
                    break
                }
                val ch = src.channels
                var k = 0
                for (i in 0 until n) for (c in 0 until ch) inter[k++] = planar[c][i]
                tr.write(inter, 0, n * ch, AudioTrack.WRITE_BLOCKING)
                pos += n
                _position.value = pos
            }
        } finally {
            track?.let {
                try { it.pause(); it.flush() } catch (_: Exception) {}
                it.release()
            }
            reader?.close()
            running = false
            _playing.value = false
        }
    }
}
