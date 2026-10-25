package com.chapterplayer.ui.widgets

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.chapterplayer.data.widgets.WidgetHost

/** Redraws every placed widget of a kind; nothing happens when none is placed. */
class GlanceWidgetHost(private val context: Context) : WidgetHost {
    override suspend fun redrawNowPlaying() = NowPlayingWidget().updateAll(context)

    override suspend fun redrawContinueListening() = Unit
}
