package com.chapterplayer.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Intent extra the notification's activity intent carries; the activity turns it into an [OpenPlayerRequest]. */
const val EXTRA_OPEN_PLAYER = "open_player"

/**
 * A one-shot "open the full player" request, from a tapped notification.
 * The navigation host takes it once something is loaded; a request that finds nothing loaded is dropped.
 */
class OpenPlayerRequest {
    private val waiting = MutableStateFlow(false)

    /** True from [request] until the request is taken or dropped. */
    val pending: StateFlow<Boolean> = waiting

    fun request() {
        waiting.value = true
    }

    /** True exactly once per request, and only when [hasBook]; otherwise the request stays waiting. */
    fun take(hasBook: Boolean): Boolean {
        if (!hasBook || !waiting.value) return false
        waiting.value = false
        return true
    }

    /** Forgets a request that found nothing loaded. */
    fun drop() {
        waiting.value = false
    }
}
