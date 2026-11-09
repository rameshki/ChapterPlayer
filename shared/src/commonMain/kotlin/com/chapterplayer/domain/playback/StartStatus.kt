package com.chapterplayer.domain.playback

/**
 * What [com.chapterplayer.data.playback.PlaybackStarter] is doing: nothing, starting a book (the server is being asked,
 * the player prepared), or the last start failed. The player screen reads it so it never says "nothing is playing" while
 * a start is under way, and shows the real reason when one failed. [Starting.attempt] tells overlapping starts apart.
 */
sealed interface StartStatus {
    data object Idle : StartStatus

    data class Starting(val bookId: String, val title: String, val attempt: Long) : StartStatus

    /** [message] is safe to show (a [com.chapterplayer.data.playback.PlaybackStartException] text). */
    data class Failed(
        val bookId: String,
        val title: String,
        val message: String,
        val attempt: Long,
        /** When it failed, on the starter's monotonic clock. */
        val atMs: Long = 0L,
    ) : StartStatus
}

/**
 * The status after the start [mine] ended with [result]: only the latest start ([current]) sets it, so a slow start that
 * ends after a newer one began never hides the newer one.
 */
fun afterStart(current: StartStatus, mine: StartStatus.Starting, result: StartStatus): StartStatus =
    if (current == mine) result else current

/** How long after a failed start the player still shows its reason; after that it is old news and the player closes. */
const val START_FAILURE_SHOWN_MS = 30_000L

/**
 * The status the player screen may show. A failure shows only when its start began while the player's state was being
 * watched ([watchedFrom]: the first attempt number begun since, so never a failure of a start made in the background,
 * by the car or a widget, nor one for another book before the screen opened) and for [START_FAILURE_SHOWN_MS] after it
 * happened ([nowMs], [StartStatus.Failed.atMs]); otherwise it reads as Idle.
 */
fun visibleStartStatus(status: StartStatus, watchedFrom: Long, nowMs: Long): StartStatus = when (status) {
    is StartStatus.Failed ->
        if (status.attempt >= watchedFrom && nowMs - status.atMs in 0 until START_FAILURE_SHOWN_MS) status else StartStatus.Idle
    else -> status
}

/** What the full player shows when no book is loaded. */
enum class PlayerEmptyView {
    /** A book is loaded: the normal player. */
    NONE,

    /** A start is under way: a loading view with the book's title, never "nothing is playing". */
    STARTING,

    /** The last start failed: its reason and Back. */
    FAILED,

    /** Nothing loaded and nothing starting (a screen restored or left on the stack after the player was released): close it. */
    LEAVE,
}

fun playerEmptyView(bookLoaded: Boolean, status: StartStatus): PlayerEmptyView = when {
    bookLoaded -> PlayerEmptyView.NONE
    status is StartStatus.Starting -> PlayerEmptyView.STARTING
    status is StartStatus.Failed -> PlayerEmptyView.FAILED
    else -> PlayerEmptyView.LEAVE
}
