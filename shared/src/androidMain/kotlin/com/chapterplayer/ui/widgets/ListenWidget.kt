package com.chapterplayer.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.widgets.EXTRA_RESUME_BOOK_ID
import com.chapterplayer.data.widgets.WidgetActions
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.domain.widgets.LISTEN_EXPANDED_MIN_HEIGHT_DP
import com.chapterplayer.domain.widgets.LISTEN_STANDARD_MIN_HEIGHT_DP
import com.chapterplayer.domain.widgets.LISTEN_WIDE_MIN_DP
import com.chapterplayer.domain.widgets.ListenLayout
import com.chapterplayer.domain.widgets.ListenSize
import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.domain.widgets.coverBookId
import com.chapterplayer.domain.widgets.listenCoverDescription
import com.chapterplayer.domain.widgets.listenEmptyText
import com.chapterplayer.domain.widgets.listenLayout
import com.chapterplayer.domain.widgets.listenPlayDescription
import com.chapterplayer.domain.widgets.listenProgressText
import com.chapterplayer.domain.widgets.listenSecondLine
import com.chapterplayer.domain.widgets.listenSize
import com.chapterplayer.shared.R
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * The Listen home screen widget: Resume and the playback controls in one (rules in `domain/widgets/ListenWidget.kt`).
 * With nothing loaded it offers the Continue Listening book; Play goes through the widget Resume path (the app starts
 * it with [com.chapterplayer.data.widgets.WidgetResumer] and opens the player). With a book loaded it shows the
 * controls, which act on that book only ([WidgetActions]). Compact is one row; standard adds the progress and every
 * button; expanded adds the cover from [ListenCoverCache] (a bitmap loaded in the app, never an address).
 * [com.chapterplayer.data.widgets.WidgetRefresher] redraws it when its state changes or a cover arrives.
 */
class ListenWidget : GlanceAppWidget(), KoinComponent {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, STANDARD, EXPANDED))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = get<WidgetStates>()
        val settings = get<AppSettings>()
        val covers = get<ListenCoverCache>()
        val initial = states.listen.first()
        val initialMode = themeOrSystem(settings.themeMode)
        provideContent {
            val state by states.listen.collectAsState(initial)
            val mode by settings.themeMode.collectAsState(initialMode)
            // Read so a cover that arrives while the session runs is drawn; the lookup itself never loads.
            val coverVersion by covers.version.collectAsState()
            val cover = if (coverVersion >= 0) state.coverBookId?.let(covers::cached) else null
            ListenContent(context, state, cover, widgetColors(mode))
        }
    }

    companion object {
        val COMPACT = DpSize(110.dp, 40.dp)
        val STANDARD = DpSize(LISTEN_WIDE_MIN_DP.dp, LISTEN_STANDARD_MIN_HEIGHT_DP.dp)
        val EXPANDED = DpSize(LISTEN_WIDE_MIN_DP.dp, LISTEN_EXPANDED_MIN_HEIGHT_DP.dp)
    }
}

class ListenWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListenWidget()
}

@Composable
private fun ListenContent(context: Context, state: ListenWidgetState, cover: Bitmap?, colors: WidgetColors) {
    val size = listenSize(LocalSize.current.width.value, LocalSize.current.height.value)
    val layout = listenLayout(size, state)
    // The body opens the player while a book is loaded, else the app.
    val open = openAppIntent(context, openPlayer = state is ListenWidgetState.Controls)
    var body = GlanceModifier.fillMaxSize().background(colors.background).cornerRadius(16.dp)
    if (open != null) body = body.clickable(actionStartActivity(open))
    when {
        state is ListenWidgetState.Empty -> Row(modifier = body.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetCoverPlaceholder(colors.textSecondary, size = 32.dp)
            Spacer(GlanceModifier.width(10.dp))
            Text(text = listenEmptyText(state), style = TextStyle(color = colors.textSecondary, fontSize = 14.sp), maxLines = 1)
        }
        size == ListenSize.COMPACT -> Row(modifier = body.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayButton(context, state, colors, 44.dp)
            Spacer(GlanceModifier.width(4.dp))
            Text(
                text = titleOf(state),
                style = TextStyle(color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        else -> Column(modifier = body.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                if (layout.showCover) {
                    Cover(state, cover, colors)
                    Spacer(GlanceModifier.width(12.dp))
                }
                Column(modifier = GlanceModifier.defaultWeight()) { BookText(state, layout, colors, roomy = size == ListenSize.EXPANDED) }
                if (state is ListenWidgetState.Resume) PlayButton(context, state, colors, 52.dp)
            }
            if (layout.showProgress) Progress(state, colors)
            if (state is ListenWidgetState.Controls) ControlsRow(context, state, layout, colors)
        }
    }
}

private fun titleOf(state: ListenWidgetState): String = when (state) {
    is ListenWidgetState.Resume -> state.title
    is ListenWidgetState.Controls -> state.title
    is ListenWidgetState.Empty -> ""
}

@Composable
private fun BookText(state: ListenWidgetState, layout: ListenLayout, colors: WidgetColors, roomy: Boolean) {
    Text(
        text = titleOf(state),
        style = TextStyle(color = colors.textPrimary, fontSize = if (roomy) 16.sp else 14.sp, fontWeight = FontWeight.Medium),
        maxLines = if (roomy) 2 else 1,
    )
    if (!layout.showSecondLine) return
    // Expanded shows the author and, while loaded, the chapter below it; smaller sizes show one line.
    if (roomy && state is ListenWidgetState.Controls && state.chapterTitle != null && state.author.isNotEmpty()) {
        Text(text = state.author, style = TextStyle(color = colors.textSecondary, fontSize = 12.sp), maxLines = 1)
    }
    listenSecondLine(state)?.let { line ->
        Text(text = line, style = TextStyle(color = colors.textSecondary, fontSize = 12.sp), maxLines = 1)
    }
}

@Composable
private fun Cover(state: ListenWidgetState, cover: Bitmap?, colors: WidgetColors) {
    val description = listenCoverDescription(state)
    if (cover == null) {
        // No cover, or not loaded yet: the placeholder look of the other widgets.
        Image(
            provider = ImageProvider(R.drawable.ic_widget_book),
            contentDescription = description,
            colorFilter = androidx.glance.ColorFilter.tint(colors.textSecondary),
            modifier = GlanceModifier.size(COVER_SIZE),
        )
    } else {
        Image(
            provider = ImageProvider(cover),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.size(COVER_SIZE).cornerRadius(10.dp),
        )
    }
}

@Composable
private fun ColumnScope.Progress(state: ListenWidgetState, colors: WidgetColors) {
    val percent = when (state) {
        is ListenWidgetState.Resume -> state.percent
        is ListenWidgetState.Controls -> state.percent
        is ListenWidgetState.Empty -> return
    }
    Spacer(GlanceModifier.height(6.dp))
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = percent / 100f,
            modifier = GlanceModifier.defaultWeight().height(4.dp).cornerRadius(2.dp),
            color = colors.accent,
            backgroundColor = colors.track,
        )
        listenProgressText(state)?.let { text ->
            Spacer(GlanceModifier.width(8.dp))
            Text(text = text, style = TextStyle(color = colors.accent, fontSize = 12.sp), maxLines = 1)
        }
    }
}

@Composable
private fun ControlsRow(context: Context, state: ListenWidgetState.Controls, layout: ListenLayout, colors: WidgetColors) {
    Spacer(GlanceModifier.height(4.dp))
    Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        if (layout.showChapterButtons) {
            WidgetIconButton(R.drawable.ic_widget_previous_chapter, "Previous chapter", colors.accent, actionRunCallback<ListenPreviousChapterAction>())
        }
        if (layout.showSkips) {
            WidgetIconButton(R.drawable.ic_widget_back, "Back 15 seconds", colors.accent, actionRunCallback<ListenSkipBackAction>(), label = "15")
        }
        PlayButton(context, state, colors, 48.dp)
        if (layout.showSkips) {
            WidgetIconButton(R.drawable.ic_widget_forward, "Forward 30 seconds", colors.accent, actionRunCallback<ListenSkipForwardAction>(), label = "30")
        }
        if (layout.showChapterButtons) {
            WidgetIconButton(R.drawable.ic_widget_next_chapter, "Next chapter", colors.accent, actionRunCallback<ListenNextChapterAction>())
        }
    }
}

/** Play (Resume: the widget Resume path) or play/pause of the loaded book. */
@Composable
private fun PlayButton(context: Context, state: ListenWidgetState, colors: WidgetColors, size: androidx.compose.ui.unit.Dp) {
    val action: Action = when (state) {
        is ListenWidgetState.Resume -> {
            val resume = openAppIntent(context, openPlayer = false, extras = mapOf(EXTRA_RESUME_BOOK_ID to state.bookId)) ?: return
            actionStartActivity(resume)
        }
        is ListenWidgetState.Controls -> actionRunCallback<ListenPlayPauseAction>()
        is ListenWidgetState.Empty -> return
    }
    val pause = state is ListenWidgetState.Controls && state.isPlaying
    WidgetIconButton(
        icon = if (pause) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        description = listenPlayDescription(state),
        tint = colors.accent,
        onClick = action,
        size = size,
    )
}

private val COVER_SIZE = 72.dp

/** The Listen buttons: they act on the loaded book only; with nothing loaded the app opens and the widget is redrawn. */
private suspend fun onListen(context: Context, glanceId: GlanceId, block: WidgetActions.() -> Boolean) =
    runWidgetButton(context, block) { ListenWidget().update(context, glanceId) }

class ListenPlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        onListen(context, glanceId) { playPause() }
}

class ListenSkipBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        onListen(context, glanceId) { skipBack() }
}

class ListenSkipForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        onListen(context, glanceId) { skipForward() }
}

class ListenPreviousChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        onListen(context, glanceId) { previousChapter() }
}

class ListenNextChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        onListen(context, glanceId) { nextChapter() }
}
