package com.chapterplayer.domain.widgets

import com.chapterplayer.domain.playback.NEEDS_CONNECTION_TO_PLAY_TEXT

/*
 * A widget's Play starts the book without opening the app (headless). When that start fails, the person sees a short
 * toast with a plain reason, the widget keeps its Resume card, and the app opens only when the person must act in it.
 */

const val SERVER_NOT_REACHABLE_TEXT = "Server not reachable"
const val CANT_PLAY_HERE_TEXT = "Can't play that book here"

/** The toast after a failed widget start, and whether the app must open (only to sign in again). */
data class WidgetStartReaction(val text: String, val openApp: Boolean)

// Texts of PlaybackStartException that mean the same thing as a shorter, plainer one.
private const val SIGN_IN_TEXT = "Sign in again in Settings"
private const val CANNOT_REACH_TEXT = "Cannot reach the server"
private const val NEEDS_ONE_ONLINE_TEXT = "This download needs a connection once before it plays offline"
private const val GENERIC_START_TEXT = "Could not start playback"

/** [message] is a start failure's safe text (null when there was none). */
fun widgetStartReaction(message: String?): WidgetStartReaction = when (message) {
    SIGN_IN_TEXT -> WidgetStartReaction(SIGN_IN_TEXT, openApp = true)
    CANNOT_REACH_TEXT -> WidgetStartReaction(SERVER_NOT_REACHABLE_TEXT, openApp = false)
    NEEDS_CONNECTION_TO_PLAY_TEXT, NEEDS_ONE_ONLINE_TEXT -> WidgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT, openApp = false)
    null, "", GENERIC_START_TEXT -> WidgetStartReaction(CANT_PLAY_HERE_TEXT, openApp = false)
    else -> WidgetStartReaction(message, openApp = false)
}

/** How long after a widget's Play a refused foreground start still opens the app. */
const val HEADLESS_FALLBACK_WINDOW_MS = 60_000L

/**
 * The system refused the player's foreground start (Android 12+, the app in the background). Only right after a widget's
 * Play does the app open so the person can carry on; a refusal for anything else (the car, a headset) opens nothing.
 */
fun openAppAfterForegroundRefusal(nowMs: Long, lastWidgetStartMs: Long?): Boolean {
    val at = lastWidgetStartMs ?: return false
    val since = nowMs - at
    return since in 0 until HEADLESS_FALLBACK_WINDOW_MS
}
