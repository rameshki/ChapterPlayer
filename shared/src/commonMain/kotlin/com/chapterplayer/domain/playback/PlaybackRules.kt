package com.chapterplayer.domain.playback

/**
 * Calculates how many milliseconds to rewind when resuming playback based on how long it was paused.
 */
fun rewindOnResumeMs(pausedForMs: Long): Long {
    return when {
        pausedForMs < 10_000 -> 0L
        pausedForMs < 300_000 -> 5_000L
        pausedForMs < 3_600_000 -> 10_000L
        else -> 20_000L
    }
}

/**
 * Calculates the starting position for playback after a pause, accounting for the rewind amount.
 */
fun resumeStart(positionMs: Long, pausedForMs: Long): Long {
    val rewind = rewindOnResumeMs(pausedForMs)
    return (positionMs - rewind).coerceAtLeast(0L)
}

/**
 * Calculates a new position by skipping backward by a specific amount of milliseconds.
 */
fun skipBackTo(positionMs: Long, byMs: Long = 15_000): Long {
    return (positionMs - byMs).coerceAtLeast(0L)
}

/**
 * Calculates a new position by skipping forward by a specific amount of milliseconds.
 */
fun skipForwardTo(positionMs: Long, durationMs: Long, byMs: Long = 30_000): Long {
    if (durationMs <= 0L) return 0L
    return (positionMs + byMs).coerceAtMost(durationMs)
}

/**
 * A predefined list of available playback speed multipliers.
 */
val PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/**
 * Returns the next available playback speed step strictly greater than the current speed, or wraps to the first step.
 */
fun nextSpeed(current: Float): Float {
    // Find the first element that is strictly greater than the current speed
    val next = PLAYBACK_SPEEDS.firstOrNull { it > current }
    return next ?: PLAYBACK_SPEEDS.first()
}

/** The slowest and fastest supported speeds; a saved or requested speed is clamped to this range. */
val MIN_PLAYBACK_SPEED: Float get() = PLAYBACK_SPEEDS.first()
val MAX_PLAYBACK_SPEED: Float get() = PLAYBACK_SPEEDS.last()
