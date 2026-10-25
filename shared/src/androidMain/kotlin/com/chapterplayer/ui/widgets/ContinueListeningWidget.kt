package com.chapterplayer.ui.widgets

import android.content.Context
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.widgets.EXTRA_RESUME_BOOK_ID
import com.chapterplayer.data.widgets.WidgetResumer
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.shared.R
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * The Continue Listening home screen widget: Home's Resume-card book (among the books that can play now) with its
 * progress and a Resume button. Resume opens the app, which starts the book through [WidgetResumer] and then shows
 * the player; the body opens the app. [com.chapterplayer.data.widgets.WidgetRefresher] redraws it when progress,
 * downloads or the network change, and the system's widget update after a boot draws it from the database.
 */
class ContinueListeningWidget : GlanceAppWidget(), KoinComponent {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = get<WidgetStates>()
        val settings = get<AppSettings>()
        val initial = states.continueListening.first()
        val initialMode = themeOrSystem(settings.themeMode)
        provideContent {
            val state by states.continueListening.collectAsState(initial)
            val mode by settings.themeMode.collectAsState(initialMode)
            ContinueContent(context, state, widgetColors(mode))
        }
    }
}

class ContinueListeningWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueListeningWidget()
}

@Composable
private fun ContinueContent(context: Context, state: ContinueWidgetState, colors: WidgetColors) {
    val open = openAppIntent(context, openPlayer = false)
    var body = GlanceModifier.fillMaxSize().background(colors.background).cornerRadius(16.dp)
    if (open != null) body = body.clickable(actionStartActivity(open))
    Row(modifier = body.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        WidgetCoverPlaceholder(colors.textSecondary, size = 36.dp)
        Spacer(GlanceModifier.width(10.dp))
        when (state) {
            ContinueWidgetState.Empty -> Text(
                text = "Nothing to continue",
                style = TextStyle(color = colors.textSecondary, fontSize = 14.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            is ContinueWidgetState.Book -> {
                Column(modifier = GlanceModifier.defaultWeight().padding(end = 4.dp)) {
                    Text(
                        text = state.title,
                        style = TextStyle(color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    if (state.author.isNotEmpty()) {
                        Text(text = state.author, style = TextStyle(color = colors.textSecondary, fontSize = 12.sp), maxLines = 1)
                    }
                    Text(
                        text = "${state.percent}% · ${state.timeLeftText}",
                        style = TextStyle(color = colors.accent, fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
                val resume = openAppIntent(context, openPlayer = false, extras = mapOf(EXTRA_RESUME_BOOK_ID to state.bookId))
                if (resume != null) {
                    WidgetIconButton(R.drawable.ic_widget_play, "Resume ${state.title}", colors.accent, actionStartActivity(resume), size = 48.dp)
                }
            }
        }
    }
}
