package com.chapterplayer.domain.widgets

import com.chapterplayer.data.playback.CANNOT_REACH_TEXT
import com.chapterplayer.data.playback.NEEDS_ONE_ONLINE_PLAY_TEXT
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.SIGN_IN_AGAIN_TEXT
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.entryContaining
import com.chapterplayer.domain.browse.hasCopy
import com.chapterplayer.domain.playback.NEEDS_CONNECTION_TO_PLAY_TEXT

/*
 * A widget's Play starts the book without opening the app (headless). It never replaces another book that is loaded,
 * and when the start fails the person sees a short toast with a plain reason while the widget keeps its Resume card.
 */

const val SERVER_NOT_REACHABLE_TEXT = "Server not reachable"
const val CANT_PLAY_HERE_TEXT = "Can't play that book here"
const val ANOTHER_BOOK_PLAYING_TEXT = "Another book is playing"

/** What a widget's Play does, from what the player holds when the tap arrives (the card may be stale). */
sealed interface WidgetPlayDecision {
    /** Nothing is loaded: start the tapped book. */
    data object Start : WidgetPlayDecision

    /** A copy of the tapped book is loaded: play (resume) that copy, never reload another one. */
    data class PlayLoaded(val loadedBookId: String) : WidgetPlayDecision

    /** Another book is loaded (from the car, the notification, the app): leave it alone and say so. */
    data object OtherBookLoaded : WidgetPlayDecision
}

/** [loadedBookId] is the player's book; [items] the merged library (a book's copies are one entry). */
fun widgetPlayDecision(loadedBookId: String?, tappedBookId: String, items: List<BrowseItem>): WidgetPlayDecision {
    val loaded = loadedBookId ?: return WidgetPlayDecision.Start
    if (loaded == tappedBookId) return WidgetPlayDecision.PlayLoaded(loaded)
    return if (entryContaining(items, tappedBookId)?.hasCopy(loaded) == true) {
        WidgetPlayDecision.PlayLoaded(loaded)
    } else {
        WidgetPlayDecision.OtherBookLoaded
    }
}

/**
 * The toast after a failed widget start, and whether to offer a way into the app ([offerApp]: a notification the person
 * can tap, only when they must act there, to sign in again; an activity is never started from the background).
 */
data class WidgetStartReaction(val text: String, val offerApp: Boolean)

/** [message] is a start failure's safe text (null when there was none); matched against the texts' own constants. */
fun widgetStartReaction(message: String?): WidgetStartReaction = when (message) {
    SIGN_IN_AGAIN_TEXT -> WidgetStartReaction(SIGN_IN_AGAIN_TEXT, offerApp = true)
    CANNOT_REACH_TEXT -> WidgetStartReaction(SERVER_NOT_REACHABLE_TEXT, offerApp = false)
    NEEDS_CONNECTION_TO_PLAY_TEXT, NEEDS_ONE_ONLINE_PLAY_TEXT -> WidgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT, offerApp = false)
    null, "", PlaybackStartException.DEFAULT_MESSAGE -> WidgetStartReaction(CANT_PLAY_HERE_TEXT, offerApp = false)
    else -> WidgetStartReaction(message, offerApp = false)
}

/** What the app does when Android refuses to start the playback service in the foreground (Android 12+). */
enum class ForegroundRefusalAction {
    /** Nothing is loaded: nothing to protect. */
    NOTHING,

    /**
     * Pause (a book playing without its foreground service and notification could be stopped by the system at any
     * moment, with no controls) and post a notification "Tap to keep listening" that opens the player.
     */
    PAUSE_AND_NOTIFY,
}

fun foregroundRefusalAction(bookLoaded: Boolean): ForegroundRefusalAction =
    if (bookLoaded) ForegroundRefusalAction.PAUSE_AND_NOTIFY else ForegroundRefusalAction.NOTHING

/** How long a widget's Play callback waits for its start (keeping the broadcast, and so the process's priority). */
const val WIDGET_START_WAIT_MS = 20_000L
