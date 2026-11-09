package com.chapterplayer.ui.widgets

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.glance.appwidget.updateAll
import com.chapterplayer.data.widgets.WidgetHost
import com.chapterplayer.domain.widgets.widgetsRedrawOnNightChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Redraws every placed widget of a kind; nothing happens when none is placed. Before Android 12 it also redraws all
 * widgets when the system switches between day and night ([widgetsRedrawOnNightChange]), since the colours of a
 * "follow system" widget are baked with the app's night mode there.
 */
class GlanceWidgetHost(private val context: Context, private val scope: CoroutineScope) : WidgetHost {

    init {
        if (widgetsRedrawOnNightChange(Build.VERSION.SDK_INT)) {
            var night = isNight(context.resources.configuration)
            context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    val now = isNight(newConfig)
                    if (now == night) return
                    night = now
                    nightChanges.update { it + 1 }
                    scope.launch {
                        redrawSafely { redrawNowPlaying() }
                        redrawSafely { redrawContinueListening() }
                        redrawSafely { redrawListen() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            })
        }
    }

    override suspend fun redrawNowPlaying() = NowPlayingWidget().updateAll(context)

    override suspend fun redrawContinueListening() = ContinueListeningWidget().updateAll(context)

    override suspend fun redrawListen() = ListenWidget().updateAll(context)

    override suspend fun listenPlaced(): Boolean = listenWidgetPlaced(context)

    override suspend fun anyPlaced(): Boolean = anyWidgetPlaced(context)

    private suspend fun redrawSafely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        private val nightChanges = MutableStateFlow(0)

        /** Moves on at each day / night switch (before Android 12), so a running widget session draws its colours again. */
        val nightTicks: StateFlow<Int> = nightChanges.asStateFlow()

        fun isNight(configuration: Configuration): Boolean =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
