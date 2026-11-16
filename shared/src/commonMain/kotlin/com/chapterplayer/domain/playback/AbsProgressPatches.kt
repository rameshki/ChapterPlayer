package com.chapterplayer.domain.playback

/**
 * One `PATCH /api/me/progress/{id}` body, in seconds. [currentTimeSec] null leaves the server's time as it is (the field
 * is not sent); [progress] is the fraction from 0 to 1.
 */
data class AbsProgressPatch(val currentTimeSec: Double?, val durationSec: Double, val progress: Double, val isFinished: Boolean)

/**
 * What Audiobookshelf is sent to set a book's progress. Audiobookshelf (checked against its `MediaProgress`
 * `applyProgressUpdate`) un-finishes a finished item whenever its time changes to a place that is not near the end, even
 * in the same request that sets it finished, and resets the time to 0 when an item is set not finished.
 *
 * - Not finished, or finished at a place past the start (a book heard to its end): one patch with the place, as always.
 * - Finished at 0 (Mark as finished, which starts the book over, and its mirrors): first set it not finished at 0, which
 *   moves the time to 0 whatever it was, then set it finished WITHOUT a time, so the time does not change and the
 *   finished flag holds. The server then holds the book finished at 0, so a later session report or close at 0 changes
 *   nothing there.
 */
fun absProgressPatches(positionMs: Long, durationMs: Long, isFinished: Boolean): List<AbsProgressPatch> {
    val positionSec = positionMs.coerceAtLeast(0L) / 1000.0
    val durationSec = durationMs.coerceAtLeast(0L) / 1000.0
    val fraction = if (durationSec > 0.0) (positionSec / durationSec).coerceIn(0.0, 1.0) else 0.0
    if (!isFinished || positionMs > 0L) return listOf(AbsProgressPatch(positionSec, durationSec, fraction, isFinished))
    return listOf(
        AbsProgressPatch(0.0, durationSec, 0.0, isFinished = false),
        AbsProgressPatch(null, durationSec, 1.0, isFinished = true),
    )
}
