package com.chapterplayer.domain.stats

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One look at the player for the listening log. [audible] means sound was coming out: playing, not buffering, with a
 * known length. [monoMs] is a monotonic clock (it does not jump when the wall clock is set).
 */
data class ListenTick(
    val bookId: String?,
    val audible: Boolean,
    val positionMs: Long,
    val speed: Float,
    val monoMs: Long,
)

/** Time listened between two ticks: [wallMs] of real time, [bookMs] of the book (wall time times the speed). */
data class Listened(val wallMs: Long, val bookMs: Long)

/** A longer gap between two ticks means the app was asleep or killed: the time is not counted. */
const val MAX_TICK_GAP_MS = 5_000L

/** How far the position may stray from wall time times speed and still count as playback rather than a seek. */
const val SEEK_TOLERANCE_MS = 2_000L

/**
 * What the interval from [previous] to [current] adds to the log, or null when it adds nothing: there was no earlier
 * tick, the book changed, the interval began paused or buffering, the gap was too long, or the position jumped (a seek).
 * The interval that ends in a pause still counts, since sound played until the pause.
 */
fun listenedBetween(previous: ListenTick?, current: ListenTick): Listened? {
    if (previous == null || previous.bookId == null || previous.bookId != current.bookId || !previous.audible) return null
    val gap = current.monoMs - previous.monoMs
    if (gap <= 0L || gap > MAX_TICK_GAP_MS) return null
    val speed = if (previous.speed > 0f) previous.speed else 1f
    val bookMs = (gap * speed.toDouble()).roundToLong()
    if (abs(current.positionMs - (previous.positionMs + bookMs)) > SEEK_TOLERANCE_MS) return null
    return Listened(gap, bookMs)
}
