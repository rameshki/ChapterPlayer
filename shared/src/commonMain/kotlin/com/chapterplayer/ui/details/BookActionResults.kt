package com.chapterplayer.ui.details

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The results of a screen's book long-press menus, whichever book they were for, in the order they came: a mark that
 * waits for the servers still reaches the snackbar after another book's menu opened. Lives as long as the screen
 * ([scope]); a result that came while the screen was away is dropped when its book is followed again, never shown late.
 */
class BookActionResults(private val scope: CoroutineScope) {
    private val channel = Channel<String>(Channel.UNLIMITED)
    private val followed = mutableSetOf<Any>()

    /** The texts for the snackbar. */
    val results: ReceiveChannel<String> get() = channel

    /** Forwards every new result of [messages] (one book's menu) and marks it [consumed]; once per [key]. */
    fun follow(key: Any, messages: StateFlow<String?>, consumed: () -> Unit) {
        if (!followed.add(key)) return
        if (messages.value != null) consumed()
        scope.launch {
            messages.filterNotNull().collect { text ->
                consumed()
                channel.send(text)
            }
        }
    }

    /** Reports a text of the menu itself ("This book is no longer in your library"). */
    fun report(text: String) {
        channel.trySend(text)
    }
}
