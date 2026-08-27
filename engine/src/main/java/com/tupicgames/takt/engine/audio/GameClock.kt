package com.tupicgames.takt.engine.audio

/**
 * Источник времени для геймплея.
 *
 * Отдельный интерфейс нужен, чтобы позже подставить ExoPlayer в видеорежиме
 * и подменять источник времени в тестах: игровой цикл не должен знать,
 * кто именно ведёт воспроизведение.
 */
interface GameClock {
    /** Позиция воспроизведения в секундах. */
    fun positionSec(): Double
    fun isRunning(): Boolean
}
