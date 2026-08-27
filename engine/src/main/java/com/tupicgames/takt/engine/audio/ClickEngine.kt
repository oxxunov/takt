package com.tupicgames.takt.engine.audio

import com.tupicgames.takt.engine.analysis.NativeLibrary
import java.io.Closeable

/**
 * Слой JNI — работает с указателем на нативный объект.
 *
 * Публичный намеренно: имена internal-деклараций Kotlin мангует суффиксом
 * модуля, и RegisterNatives их бы не нашёл.
 */
object NativeClickEngine {
    init { NativeLibrary.ensureLoaded() }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeTrigger(handle: Long, variant: Int)
    external fun nativeStartMetronome(handle: Long, periodSec: Float)
    external fun nativeStopMetronome(handle: Long)
    external fun nativeLastClickNanos(handle: Long): Long
}

/**
 * Низколатентный вывод кликов через AAudio.
 *
 * Экземпляр, а не глобальный синглтон: раньше калибровка и игра дёргали
 * общий start/stop и гасили поток друг у друга при переходе между экранами.
 * Владелец создаёт объект и закрывает его в своём жизненном цикле.
 */
class ClickEngine : Closeable {

    private var handle: Long = 0L

    val isOpen: Boolean get() = handle != 0L

    /** @return false, если устройство не дало открыть низколатентный поток. */
    fun open(): Boolean {
        if (handle != 0L) return true
        handle = NativeClickEngine.nativeCreate()
        return handle != 0L
    }

    fun hit() {
        if (handle != 0L) NativeClickEngine.nativeTrigger(handle, VARIANT_HIT)
    }

    fun startMetronome(periodSec: Float) {
        if (handle != 0L) NativeClickEngine.nativeStartMetronome(handle, periodSec)
    }

    fun stopMetronome() {
        if (handle != 0L) NativeClickEngine.nativeStopMetronome(handle)
    }

    /** Момент (nanoTime), когда последний щелчок реально прозвучал. 0 — данных нет. */
    fun lastClickNanos(): Long =
        if (handle != 0L) NativeClickEngine.nativeLastClickNanos(handle) else 0L

    override fun close() {
        if (handle == 0L) return
        NativeClickEngine.nativeDestroy(handle)
        handle = 0L
    }

    private companion object {
        const val VARIANT_HIT = 1
    }
}
