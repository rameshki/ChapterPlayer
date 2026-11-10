package com.chapterplayer.ui.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.data.widgets.WidgetActions
import com.chapterplayer.data.widgets.WidgetRefresher
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.domain.widgets.NOW_PLAYING_COVER_MIN_DP
import com.chapterplayer.domain.widgets.NOW_PLAYING_WIDE_MIN_DP
import com.chapterplayer.domain.widgets.NowPlayingWidgetState
import com.chapterplayer.domain.widgets.WidgetDimens
import com.chapterplayer.domain.widgets.coverBookId
import com.chapterplayer.domain.widgets.nowPlayingEmptyText
import com.chapterplayer.domain.widgets.nowPlayingIsWide
import com.chapterplayer.domain.widgets.nowPlayingShowsCover
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
 * the title and back 15 / play-pause / forward 30, from [NOW_PLAYING_COVER_MIN_DP] with the book's cover first (the
 * shared look of WidgetUi.kt: rounded card, round play button). Tapping the body opens the app on the player. The buttons act
 * on the loaded book through the app's one controller (see [WidgetActions]). With nothing loaded it offers the last book
 * (the Listen and Continue Listening book) with Play, which starts it without opening the app ([WidgetPlayAction]);
 * "Nothing played yet" only when there is no history. [com.chapterplayer.data.widgets.WidgetRefresher] redraws it when its state changes.
 */
class NowPlayingWidget : GlanceAppWidget(), KoinComponent {

    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE, WIDE_COVER))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = get<WidgetStates>()
        val settings = get<AppSettings>()
        val covers = get<WidgetCoverCache>()
        // Placed, resized or updated by the system: ask for the loaded book's cover if it is missing.
        get<WidgetRefresher>().requestCover()
        val initial = states.nowPlaying.first()
        val initialMode = themeOrSystem(settings.themeMode)
        provideContent {
            val state by states.nowPlaying.collectAsState(initial)
            val mode by settings.themeMode.collectAsState(initialMode)
            // Read so a day / night switch before Android 12 draws the baked colours again.
            GlanceWidgetHost.nightTicks.collectAsState().value
            val coverVersion by covers.version.collectAsState()
            val cover = if (coverVersion >= 0) state.coverBookId?.let(covers::cached) else null
            NowPlayingContent(context, state, cover, widgetColors(mode, context))
        }
    }

    companion object {
        val NARROW = DpSize(40.dp, 40.dp)
        val WIDE = DpSize(NOW_PLAYING_WIDE_MIN_DP.dp, 40.dp)
        val WIDE_COVER = DpSize(NOW_PLAYING_COVER_MIN_DP.dp, 40.dp)
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver(), KoinComponent {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()

    // The last Now Playing widget removed: covers are loaded only while some widget is placed.
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        get<WidgetRefresher>().widgetsRemoved()
    }
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
private fun NowPlayingContent(context: Context, state: NowPlayingWidgetState, cover: Bitmap?, colors: WidgetColors) {
    val width = LocalSize.current.width.value
    val wide = nowPlayingIsWide(width)
    val open = openAppIntent(context, openPlayer = state is NowPlayingWidgetState.Book)
    var body = cardModifier(colors)
    if (open != null) body = body.clickable(actionStartActivity(open))
    Row(
        modifier = body.padding(horizontal = WidgetDimens.ROW_PADDING_DP.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        when (state) {
            // Nothing loaded: the last book with Play (the same book as Listen and Continue Listening), started headless.
            is NowPlayingWidgetState.Resume -> if (wide) WideResume(state, cover, nowPlayingShowsCover(width), colors) else ResumePlay(state, colors)
            // Truly nothing to offer ("Nothing played yet", or nothing that can play now); Idle is not shown by the widget.
            is NowPlayingWidgetState.Empty, NowPlayingWidgetState.Idle -> {
                WidgetCover(null, 32.dp, colors)
                if (wide) {
                    Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
                    Text(
                        text = nowPlayingEmptyText(state as? NowPlayingWidgetState.Empty ?: NowPlayingWidgetState.Empty(everPlayed = false)),
                        style = TextStyle(color = colors.textSecondary, fontSize = 14.sp),
                        maxLines = 1,
                    )
                }
            }
            is NowPlayingWidgetState.Book -> if (wide) WideBook(state, cover, nowPlayingShowsCover(width), colors) else PlayPause(state, colors)
        }
    }
}

@Composable
private fun RowScope.WideBook(state: NowPlayingWidgetState.Book, cover: Bitmap?, showCover: Boolean, colors: WidgetColors) {
    if (showCover) {
        WidgetCover(cover, WidgetDimens.ROW_COVER_DP.dp, colors)
        Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
    }
    Column(modifier = GlanceModifier.defaultWeight().padding(end = 4.dp)) {
        Text(
            text = state.title,
            style = TextStyle(color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        val second = state.chapterTitle ?: state.author
        if (second.isNotEmpty()) {
            Text(text = second, style = TextStyle(color = colors.textSecondary, fontSize = 12.sp), maxLines = 1)
        }
    }
    if (state.canSkip) {
        WidgetIconButton(R.drawable.ic_widget_back, "Back 15 seconds", colors.icon, actionRunCallback<SkipBackAction>(), label = "15")
    }
    PlayPause(state, colors)
    if (state.canSkip) {
        WidgetIconButton(R.drawable.ic_widget_forward, "Forward 30 seconds", colors.icon, actionRunCallback<SkipForwardAction>(), label = "30")
    }
}

@Composable
private fun RowScope.WideResume(state: NowPlayingWidgetState.Resume, cover: Bitmap?, showCover: Boolean, colors: WidgetColors) {
    if (showCover) {
        WidgetCover(cover, WidgetDimens.ROW_COVER_DP.dp, colors)
        Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
    }
    Column(modifier = GlanceModifier.defaultWeight().padding(end = 4.dp)) {
        Text(
            text = state.title,
            style = TextStyle(color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(text = state.timeLeftText, style = TextStyle(color = colors.textSecondary, fontSize = 12.sp), maxLines = 1)
    }
    ResumePlay(state, colors)
}

/** Play for the last book: starts it without opening the app ([WidgetPlayAction]). */
@Composable
private fun ResumePlay(state: NowPlayingWidgetState.Resume, colors: WidgetColors) {
    WidgetPlayButton(
        pause = false,
        description = "Play ${state.title}",
        onClick = widgetPlayAction(state.bookId),
        colors = colors,
        target = WidgetDimens.ROW_PLAY_DP.dp,
    )
}

@Composable
private fun PlayPause(state: NowPlayingWidgetState.Book, colors: WidgetColors) {
    WidgetPlayButton(
        pause = state.isPlaying,
        description = if (state.isPlaying) "Pause" else "Play",
        onClick = actionRunCallback<PlayPauseAction>(),
        colors = colors,
        target = WidgetDimens.ROW_PLAY_DP.dp,
    )
}

/**
 * Runs a button on the main thread, as the controller requires. When nothing is loaded (the widget still shows a
 * book from before the app was gone) the app opens instead, and the widget is redrawn.
 */
private suspend fun onMain(context: Context, glanceId: GlanceId, block: WidgetActions.() -> Boolean) =
    runWidgetButton(context, block) { NowPlayingWidget().update(context, glanceId) }

/**
 * A widget button (Now Playing, Listen): [block] runs on the main thread; when it did nothing because no book is
 * loaded, the app opens instead and [redraw] draws the widget again.
 */
internal suspend fun runWidgetButton(context: Context, block: WidgetActions.() -> Boolean, redraw: suspend () -> Unit) {
    val actions = object : KoinComponent {}.get<WidgetActions>()
    val acted = withContext(Dispatchers.Main) { actions.block() }
    if (acted) return
    openAppIntent(context, openPlayer = false)?.let { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // The system refused a start from the background; the redraw below shows the Resume card again.
        }
    }
    try {
        redraw()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        onMain(context, glanceId) { playPause() }
}

class SkipBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        onMain(context, glanceId) { skipBack() }
}

class SkipForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        onMain(context, glanceId) { skipForward() }
}
