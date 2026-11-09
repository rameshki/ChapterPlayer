package com.chapterplayer.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.widgets.EXTRA_RESUME_BOOK_ID
import com.chapterplayer.data.widgets.WidgetRefresher
import com.chapterplayer.data.widgets.WidgetResumer
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.domain.widgets.WidgetDimens
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * The Continue Listening home screen widget: Home's Resume-card book (among the books that can play now) with its
 * cover, progress and a round Resume button (the shared look of WidgetUi.kt). Resume opens the app, which starts the book through [WidgetResumer] and then shows
 * the player; the body opens the app. [com.chapterplayer.data.widgets.WidgetRefresher] redraws it when progress,
 * downloads or the network change, and the system's widget update after a boot draws it from the database.
 */
class ContinueListeningWidget : GlanceAppWidget(), KoinComponent {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = get<WidgetStates>()
        val settings = get<AppSettings>()
        val covers = get<WidgetCoverCache>()
        // Placed, resized or updated by the system: ask for the offered book's cover if it is missing.
        get<WidgetRefresher>().requestCover()
        val initial = states.continueListening.first()
        val initialMode = themeOrSystem(settings.themeMode)
        provideContent {
            val state by states.continueListening.collectAsState(initial)
            val mode by settings.themeMode.collectAsState(initialMode)
            val coverVersion by covers.version.collectAsState()
            val cover = if (coverVersion >= 0) (state as? ContinueWidgetState.Book)?.bookId?.let(covers::cached) else null
            ContinueContent(context, state, cover, widgetColors(mode))
        }
    }
}

class ContinueListeningWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueListeningWidget()
}

@Composable
private fun ContinueContent(context: Context, state: ContinueWidgetState, cover: Bitmap?, colors: WidgetColors) {
    val open = openAppIntent(context, openPlayer = false)
    var body = cardModifier(colors)
    if (open != null) body = body.clickable(actionStartActivity(open))
    Row(modifier = body.padding(horizontal = WidgetDimens.ROW_PADDING_DP.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            ContinueWidgetState.Empty -> {
                WidgetCover(null, 32.dp, colors)
                Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
                Text(
                    text = "Nothing to continue",
                    style = TextStyle(color = colors.textSecondary, fontSize = 14.sp),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
            is ContinueWidgetState.Book -> {
                WidgetCover(cover, WidgetDimens.ROW_COVER_DP.dp, colors)
                Spacer(GlanceModifier.width(WidgetDimens.GAP_DP.dp))
                Column(modifier = GlanceModifier.defaultWeight().padding(end = 4.dp)) {
                    Text(
                        text = state.title,
                        style = TextStyle(color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    if (state.author.isNotEmpty()) {
                        Text(text = state.author, style = TextStyle(color = colors.textSecondary, fontSize = 12.sp), maxLines = 1)
                    }
                    Text(
                        text = "${state.percent}% · ${state.timeLeftText}",
                        style = TextStyle(color = colors.textSecondary, fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
                val resume = openAppIntent(context, openPlayer = false, extras = mapOf(EXTRA_RESUME_BOOK_ID to state.bookId))
                if (resume != null) {
                    WidgetPlayButton(
                        pause = false,
                        description = "Resume ${state.title}",
                        onClick = actionStartActivity(resume),
                        colors = colors,
                        target = WidgetDimens.ROW_PLAY_DP.dp,
                    )
                }
            }
        }
    }
}
