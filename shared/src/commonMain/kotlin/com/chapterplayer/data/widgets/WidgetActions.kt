package com.chapterplayer.data.widgets

import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.domain.widgets.WidgetCommand
import com.chapterplayer.domain.widgets.canSkip
import com.chapterplayer.domain.widgets.playPauseCommand

/**
 * The Now Playing widget's buttons. They act through the app's one [PlaybackController] (which connects to the
 * service when needed) on the book already loaded, and never load one. Each returns false when nothing is loaded
 * (a widget left over from before the app was gone), so the widget opens the app instead. Call from the main thread.
 */
class WidgetActions(private val controller: PlaybackController) {

    fun playPause(): Boolean {
        when (playPauseCommand(controller.state.value)) {
            WidgetCommand.PLAY -> controller.play()
            WidgetCommand.PAUSE -> controller.pause()
            WidgetCommand.NONE -> return false
        }
        return true
    }

    fun skipBack(): Boolean = skip { controller.skipBack() }

    fun skipForward(): Boolean = skip { controller.skipForward() }

    private inline fun skip(action: () -> Unit): Boolean {
        val state = controller.state.value
        if (state.bookId == null) return false
        if (canSkip(state)) action()
        return true
    }
}
