package com.chapterplayer.ui.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.data.widgets.WidgetActions
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.domain.widgets.NOW_PLAYING_WIDE_MIN_DP
import com.chapterplayer.domain.widgets.NowPlayingWidgetState
import com.chapterplayer.domain.widgets.nowPlayingIsWide
import com.chapterplayer.shared.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * The Now Playing home screen widget. Narrow it is play/pause only; from [NOW_PLAYING_WIDE_MIN_DP] wide it shows
 * the title and back 15 / play-pause / forward 30. Tapping the body opens the app on the player. The buttons act
 * on the loaded book through the app's one controller (see [WidgetActions]); with nothing loaded the widget only
 * opens the app. [com.chapterplayer.data.widgets.WidgetRefresher] redraws it when its state changes.
 */
class NowPlayingWidget : GlanceAppWidget(), KoinComponent {

    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = get<WidgetStates>()
        val settings = get<AppSettings>()
        val initial = states.nowPlaying.first()
        val initialMode = themeOrSystem(settings.themeMode)
        provideContent {
            val state by states.nowPlaying.collectAsState(initial)
            val mode by settings.themeMode.collectAsState(initialMode)
            NowPlayingContent(context, state, widgetColors(mode))
        }
    }

    companion object {
        val NARROW = DpSize(48.dp, 48.dp)
        val WIDE = DpSize(NOW_PLAYING_WIDE_MIN_DP.dp, 48.dp)
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** The theme setting, or "follow system" when it cannot be read. */
internal suspend fun themeOrSystem(mode: Flow<ThemeMode>): ThemeMode = try {
    mode.first()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    ThemeMode.SYSTEM
}

@Composable
private fun NowPlayingContent(context: Context, state: NowPlayingWidgetState, colors: WidgetColors) {
    val wide = nowPlayingIsWide(LocalSize.current.width.value)
    val open = openAppIntent(context, openPlayer = state is NowPlayingWidgetState.Book)
    var body = GlanceModifier.fillMaxSize().background(colors.background).cornerRadius(16.dp)
    if (open != null) body = body.clickable(actionStartActivity(open))
    Row(
        modifier = body.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        when (state) {
            NowPlayingWidgetState.Idle -> {
                WidgetCoverPlaceholder(colors.textSecondary)
                if (wide) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = "Nothing playing",
                        style = TextStyle(color = colors.textSecondary, fontSize = 14.sp),
                        maxLines = 1,
                    )
                }
            }
            is NowPlayingWidgetState.Book -> if (wide) WideBook(state, colors) else PlayPause(state, colors)
        }
    }
}

@Composable
private fun RowScope.WideBook(state: NowPlayingWidgetState.Book, colors: WidgetColors) {
    Column(modifier = GlanceModifier.defaultWeight().padding(end = 4.dp)) {
        Text(
            text = state.title,
            style = TextStyle(color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        val second = state.chapterTitle ?: state.author
        if (second.isNotEmpty()) {
            Text(text = second, style = TextStyle(color = colors.textSecondary, fontSize = 12.sp), maxLines = 1)
        }
    }
    if (state.canSkip) {
        WidgetIconButton(R.drawable.ic_widget_back, "Back 15 seconds", colors.accent, actionRunCallback<SkipBackAction>(), label = "15")
    }
    PlayPause(state, colors)
    if (state.canSkip) {
        WidgetIconButton(R.drawable.ic_widget_forward, "Forward 30 seconds", colors.accent, actionRunCallback<SkipForwardAction>(), label = "30")
    }
}

@Composable
private fun PlayPause(state: NowPlayingWidgetState.Book, colors: WidgetColors) {
    Box(contentAlignment = Alignment.Center) {
        WidgetIconButton(
            icon = if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            description = if (state.isPlaying) "Pause" else "Play",
            tint = colors.accent,
            onClick = actionRunCallback<PlayPauseAction>(),
            size = 44.dp,
        )
    }
}

/** The controller is called on the main thread, as it requires. */
private suspend fun onMain(block: WidgetActions.() -> Unit) {
    val actions = object : KoinComponent {}.get<WidgetActions>()
    withContext(Dispatchers.Main) { actions.block() }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = onMain { playPause() }
}

class SkipBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = onMain { skipBack() }
}

class SkipForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = onMain { skipForward() }
}
