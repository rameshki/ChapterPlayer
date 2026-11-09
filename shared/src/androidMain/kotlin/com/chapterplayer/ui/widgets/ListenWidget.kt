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
import androidx.glance.LocalSize
import androidx.glance.action.Action
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.widgets.EXTRA_RESUME_BOOK_ID
import com.chapterplayer.data.widgets.WidgetActions
import com.chapterplayer.data.widgets.WidgetRefresher
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.domain.widgets.LISTEN_BUCKETS
import com.chapterplayer.domain.widgets.ListenDimens
import com.chapterplayer.domain.widgets.ListenLayout
import com.chapterplayer.domain.widgets.ListenSize
import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.domain.widgets.WidgetDimens
import com.chapterplayer.domain.widgets.coverBookId
import com.chapterplayer.domain.widgets.listenBarSecondLine
import com.chapterplayer.domain.widgets.listenEmptyText
import com.chapterplayer.domain.widgets.listenLayout
import com.chapterplayer.domain.widgets.listenMetaText
import com.chapterplayer.domain.widgets.listenPlayDescription
import com.chapterplayer.domain.widgets.listenSecondLine
import com.chapterplayer.shared.R
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * The Listen home screen widget: Resume and the playback controls in one (rules in `domain/widgets/ListenWidget.kt`,
 * look in `WidgetUi.kt`). With nothing loaded it offers the Continue Listening book; Play goes through the widget Resume
 * path (the app starts it with [com.chapterplayer.data.widgets.WidgetResumer] and opens the player). With a book loaded
 * it shows the controls, which act on that book only ([WidgetActions]). Sizes: a mini bar, a slim bar, a square, a wide
 * card and a large card, each with the cover from [WidgetCoverCache] but the mini bar (a bitmap loaded in the app,
 * never an address). [com.chapterplayer.data.widgets.WidgetRefresher] redraws it when its state changes or a cover arrives.
 */
class ListenWidget : GlanceAppWidget(), KoinComponent {

    // Laid out at every bucket of LISTEN_BUCKETS; the launcher shows the closest one that fits.
    override val sizeMode = SizeMode.Responsive(LISTEN_BUCKETS.map { DpSize(it.widthDp.dp, it.heightDp.dp) }.toSet())

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = get<WidgetStates>()
        val settings = get<AppSettings>()
        val covers = get<WidgetCoverCache>()
        val refresher = get<WidgetRefresher>()
        // Drawn means placed (or resized, or updated by the system): share the state from now on and ask for a missing cover.
        refresher.listenPlacementChanged(true)
        refresher.requestCover()
        val initial = states.listen.first()
        val initialMode = themeOrSystem(settings.themeMode)
        provideContent {
            val state by refresher.listen.collectAsState(initial)
            val mode by settings.themeMode.collectAsState(initialMode)
            // Read so a day / night switch before Android 12 draws the baked colours again.
            GlanceWidgetHost.nightTicks.collectAsState().value
            // Read so a cover that arrives while the session runs is drawn; the lookup itself never loads.
            val coverVersion by covers.version.collectAsState()
            val cover = if (coverVersion >= 0) state.coverBookId?.let(covers::cached) else null
            ListenContent(context, state, cover, widgetColors(mode, context))
        }
    }
}

class ListenWidgetReceiver : GlanceAppWidgetReceiver(), KoinComponent {
    override val glanceAppWidget: GlanceAppWidget = ListenWidget()

    // The first Listen widget placed and the last one removed: the refresher computes its state only in between.
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        get<WidgetRefresher>().listenPlacementChanged(true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        get<WidgetRefresher>().listenPlacementChanged(false)
        get<WidgetRefresher>().widgetsRemoved()
    }
}

@Composable
private fun ListenContent(context: Context, state: ListenWidgetState, cover: Bitmap?, colors: WidgetColors) {
    val width = LocalSize.current.width.value
    val layout = listenLayout(width, LocalSize.current.height.value, state)
    // The body opens the player while a book is loaded, else the app.
    val open = openAppIntent(context, openPlayer = state is ListenWidgetState.Controls)
    var body = cardModifier(colors)
    if (open != null) body = body.clickable(actionStartActivity(open))
    val padded = body.padding(horizontal = layout.horizontalPaddingDp.dp, vertical = layout.verticalPaddingDp.dp)
    if (state is ListenWidgetState.Empty) {
        Row(modifier = body.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(null, 32.dp, colors)
            Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
            Text(text = listenEmptyText(state), style = TextStyle(color = colors.textSecondary, fontSize = 14.sp), maxLines = 1)
        }
        return
    }
    when (layout.size) {
        ListenSize.MINI -> Row(modifier = padded, verticalAlignment = Alignment.CenterVertically) {
            PlayButton(context, state, colors, layout.playButtonDp.dp)
            Spacer(GlanceModifier.width(4.dp))
            Title(state, layout, colors, GlanceModifier.defaultWeight())
        }
        // A slim bar: cover, title and one line, the buttons on the right.
        ListenSize.BAR -> Row(modifier = padded, verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(cover, layout.coverDp.dp, colors)
            Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Title(state, layout, colors)
                listenBarSecondLine(state)?.let { Secondary(it, colors) }
            }
            Spacer(GlanceModifier.width(ListenDimens.BUTTONS_GAP_DP.dp))
            Buttons(context, state, layout, colors)
        }
        // Cover at the top left, the title, the round play button with the time left beside it.
        ListenSize.SQUARE -> Column(modifier = padded) {
            WidgetCover(cover, layout.coverDp.dp, colors)
            Spacer(GlanceModifier.height(ListenDimens.PROGRESS_GAP_DP.dp))
            Title(state, layout, colors)
            Spacer(GlanceModifier.height(ListenDimens.LINE_GAP_DP.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayButton(context, state, colors, layout.playButtonDp.dp)
                listenMetaText(state)?.let {
                    Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
                    Secondary(it, colors, ListenDimens.META_SP)
                }
            }
        }
        // Cover at the left; title, author or chapter, progress, time left and the buttons beside it.
        ListenSize.WIDE -> Row(modifier = padded, verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(cover, layout.coverDp.dp, colors)
            Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Title(state, layout, colors)
                if (layout.showSecondLine) listenSecondLine(state)?.let { Secondary(it, colors) }
                if (layout.showProgress) {
                    Spacer(GlanceModifier.height(ListenDimens.PROGRESS_GAP_DP.dp))
                    WidgetProgressBar(percentOf(state), colors, GlanceModifier.fillMaxWidth())
                }
                Spacer(GlanceModifier.height(ListenDimens.LINE_GAP_DP.dp))
                listenMetaText(state)?.let { Secondary(it, colors, ListenDimens.META_SP) }
                Spacer(GlanceModifier.height(ListenDimens.BUTTONS_GAP_DP.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Buttons(context, state, layout, colors) }
            }
        }
        // A bigger cover with the title, author and chapter; the progress with the time left; the full control row.
        ListenSize.LARGE -> Column(modifier = padded, verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                WidgetCover(cover, layout.coverDp.dp, colors)
                Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Title(state, layout, colors)
                    if (layout.showAuthorAndChapter && state is ListenWidgetState.Controls) {
                        Secondary(state.author, colors)
                        state.chapterTitle?.let { Secondary(it, colors) }
                    } else if (layout.showSecondLine) {
                        listenSecondLine(state)?.let { Secondary(it, colors) }
                    }
                }
            }
            Spacer(GlanceModifier.height(ListenDimens.PROGRESS_GAP_DP.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WidgetProgressBar(percentOf(state), colors, GlanceModifier.defaultWeight())
                listenMetaText(state)?.let {
                    Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
                    Secondary(it, colors, ListenDimens.META_SP)
                }
            }
            Spacer(GlanceModifier.height(ListenDimens.BUTTONS_GAP_DP.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                Buttons(context, state, layout, colors)
            }
        }
    }
}

private fun titleOf(state: ListenWidgetState): String = when (state) {
    is ListenWidgetState.Resume -> state.title
    is ListenWidgetState.Controls -> state.title
    is ListenWidgetState.Empty -> ""
}

private fun percentOf(state: ListenWidgetState): Int = when (state) {
    is ListenWidgetState.Resume -> state.percent
    is ListenWidgetState.Controls -> state.percent
    is ListenWidgetState.Empty -> 0
}

@Composable
private fun Title(state: ListenWidgetState, layout: ListenLayout, colors: WidgetColors, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text = titleOf(state),
        style = TextStyle(color = colors.textPrimary, fontSize = layout.titleSp.sp, fontWeight = FontWeight.Bold),
        maxLines = layout.titleLines,
        modifier = modifier,
    )
}

@Composable
private fun Secondary(text: String, colors: WidgetColors, sizeSp: Float = ListenDimens.SECOND_SP) {
    Text(text = text, style = TextStyle(color = colors.textSecondary, fontSize = sizeSp.sp), maxLines = 1)
}

/** The play button and, for a loaded book where [layout] has room, back 15 / forward 30 and the chapter buttons. */
@Composable
private fun Buttons(context: Context, state: ListenWidgetState, layout: ListenLayout, colors: WidgetColors) {
    val side = layout.sideButtonDp.dp
    if (layout.showChapterButtons) {
        WidgetIconButton(R.drawable.ic_widget_previous_chapter, "Previous chapter", colors.icon, actionRunCallback<ListenPreviousChapterAction>(), size = side)
    }
    if (layout.showSkips) {
        WidgetIconButton(R.drawable.ic_widget_back, "Back 15 seconds", colors.icon, actionRunCallback<ListenSkipBackAction>(), size = side, label = "15")
    }
    PlayButton(context, state, colors, layout.playButtonDp.dp)
    if (layout.showSkips) {
        WidgetIconButton(R.drawable.ic_widget_forward, "Forward 30 seconds", colors.icon, actionRunCallback<ListenSkipForwardAction>(), size = side, label = "30")
    }
    if (layout.showChapterButtons) {
        WidgetIconButton(R.drawable.ic_widget_next_chapter, "Next chapter", colors.icon, actionRunCallback<ListenNextChapterAction>(), size = side)
    }
}

/** Play (Resume: the widget Resume path) or play/pause of the loaded book: the round button. */
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
    WidgetPlayButton(
        pause = state is ListenWidgetState.Controls && state.isPlaying,
        description = listenPlayDescription(state),
        onClick = action,
        colors = colors,
        target = size,
    )
}

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
