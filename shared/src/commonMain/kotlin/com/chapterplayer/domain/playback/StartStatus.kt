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
    data class Failed(val bookId: String, val title: String, val message: String, val attempt: Long) : StartStatus
}

/**
 * The status after the start [mine] ended with [result]: only the latest start ([current]) sets it, so a slow start that
 * ends after a newer one began never hides the newer one.
 */
fun afterStart(current: StartStatus, mine: StartStatus.Starting, result: StartStatus): StartStatus =
    if (current == mine) result else current

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
