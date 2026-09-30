package com.oxxunov.voiceenhance.audioio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.oxxunov.voiceenhance.engine.Db
import com.oxxunov.voiceenhance.engine.PcmFile
import com.oxxunov.voiceenhance.engine.PcmWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Рекордер: сначала мониторинг уровня (без записи), затем запись в float-файл.
 * Источник UNPROCESSED (без шумодава/АРУ телефона), если устройство его поддерживает, иначе VOICE_RECOGNITION.
 * Захват в float, если устройство умеет, иначе 16-bit.
 */
class AudioRecorder(private val context: Context) {

    data class Level(
        val active: Boolean = false,
        val recording: Boolean = false,
        val peakDb: Double = -120.0,
        val rmsDb: Double = -120.0,
        val clipped: Boolean = false,
        val seconds: Double = 0.0,
        val sourceLabel: String = "",
        val sampleRate: Int = 0,
        val channels: Int = 0,
    )

    private val _level = MutableStateFlow(Level())
    val level: StateFlow<Level> = _level.asStateFlow()

    @Volatile private var running = false
    @Volatile private var writer: PcmWriter? = null
    @Volatile private var clipLatched = false
    private var thread: Thread? = null
    private var sampleRate = 48000
    private var channels = 1

    fun unprocessedSupported(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    /** Запускает захват с микрофона в режиме мониторинга. Требует разрешение RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start(requestedRate: Int, requestedChannels: Int) {
        stopCapture()
        val unprocessed = unprocessedSupported()
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val mask = if (requestedChannels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        var isFloat = true
        var rec = create(source, requestedRate, mask, AudioFormat.ENCODING_PCM_FLOAT, requestedChannels, 4)
        if (rec == null) {
            isFloat = false
            rec = create(source, requestedRate, mask, AudioFormat.ENCODING_PCM_16BIT, requestedChannels, 2)
        }
        val r = rec ?: throw IllegalStateException("Микрофон недоступен с такими настройками")
        sampleRate = r.sampleRate
        channels = r.channelCount
        clipLatched = false
        running = true
        _level.value = Level(
            active = true,
            sourceLabel = (if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION") + if (isFloat) ", float" else ", 16-bit",
            sampleRate = sampleRate,
            channels = channels,
        )
        thread = Thread({ loop(r, isFloat) }, "recorder").also { it.start() }
    }

    private fun create(source: Int, sr: Int, mask: Int, enc: Int, ch: Int, bytes: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(sr, mask, enc)
        if (min <= 0) return null
        return try {
            val r = AudioRecord(source, sr, mask, enc, max(min * 4, sr * ch * bytes / 5))
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                r.release(); null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loop(rec: AudioRecord, isFloat: Boolean) {
        val n = 1024 * channels
        val fa = FloatArray(n)
        val sa = ShortArray(n)
        var recordedFrames = 0L
        try {
            rec.startRecording()
            while (running) {
                val got = if (isFloat) rec.read(fa, 0, n, AudioRecord.READ_BLOCKING) else rec.read(sa, 0, n)
                if (got < 0) break
                if (got == 0) continue
                if (!isFloat) for (i in 0 until got) fa[i] = sa[i] / 32768f
                var peak = 0.0
                var sum = 0.0
                for (i in 0 until got) {
                    val v = fa[i].toDouble()
                    val a = kotlin.math.abs(v)
                    if (a > peak) peak = a
                    sum += v * v
                }
                if (peak >= 0.99) clipLatched = true
                val w = writer
                if (w != null) {
                    w.writeInterleaved(fa, got)
                    recordedFrames += got / channels
                } else {
                    recordedFrames = 0
                }
                _level.value = _level.value.copy(
                    recording = w != null,
                    peakDb = Db.fromLin(peak),
                    rmsDb = Db.fromLin(sqrt(sum / got)),
                    clipped = clipLatched,
                    seconds = recordedFrames.toDouble() / sampleRate,
                )
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    /** Начинает запись в файл (мониторинг должен быть запущен). */
    fun beginWriting(file: File) {
        if (!running || writer != null) return
        clipLatched = false
        writer = PcmWriter(file, sampleRate, channels)
    }

    fun resetClip() {
        clipLatched = false
    }

    /** Останавливает захват. Возвращает записанный файл, если шла запись. */
    fun stop(): PcmFile? {
        stopCapture()
        val w = writer
        writer = null
        _level.value = Level()
        return w?.finish()
    }

    private fun stopCapture() {
        running = false
        thread?.join(2000)
        thread = null
    }
}
