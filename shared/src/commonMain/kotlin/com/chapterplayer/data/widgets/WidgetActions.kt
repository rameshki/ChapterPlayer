package com.chapterplayer.data.widgets

import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.domain.widgets.WidgetCommand
import com.chapterplayer.domain.widgets.canSkip
import com.chapterplayer.domain.widgets.playPauseCommand

/**
 * The Now Playing widget's buttons. They act through the app's one [PlaybackController] (which connects to the
 * service when needed) on the book already loaded, and never load one. Call from the main thread.
 */
class WidgetActions(private val controller: PlaybackController) {

    fun playPause() {
        when (playPauseCommand(controller.state.value)) {
            WidgetCommand.PLAY -> controller.play()
            WidgetCommand.PAUSE -> controller.pause()
            WidgetCommand.NONE -> Unit
        }
    }

    fun skipBack() {
        if (canSkip(controller.state.value)) controller.skipBack()
    }

    fun skipForward() {
        if (canSkip(controller.state.value)) controller.skipForward()
    }
}
