package com.chapterplayer.ui.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.core.app.ApplicationProvider
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.domain.widgets.LISTEN_BUCKETS
import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.domain.widgets.NowPlayingWidgetState
import com.chapterplayer.ui.widgets.ContinueContent
import com.chapterplayer.ui.widgets.ListenContent
import com.chapterplayer.ui.widgets.NowPlayingContent
import com.chapterplayer.ui.widgets.WidgetColors
import com.chapterplayer.ui.widgets.widgetColors
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of the three home screen widgets (review R2): each is composed by Glance at an exact size into
 * RemoteViews, inflated into a host view and captured. Now Playing at its three widths, Listen at every size bucket and
 * Continue Listening, in dark and light, with a loaded book, the Resume offer and the empty state. Synthetic titles only.
 * Record with recordRoborazziAndroidHostTest (docs/architecture/testing.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class WidgetScreenshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val book = NowPlayingWidgetState.Book(
        "A Reasonably Titled Book", "A. Writer", "Chapter 1. The Beginning", isPlaying = true, canSkip = true, bookId = "b1",
    )
    private val longBook = book.copy(
        title = "An Extraordinarily Long Audiobook Title That Cannot Possibly Fit",
        chapterTitle = "Chapter 12. A Chapter Name That Is Far Too Long", isPlaying = false,
    )
    private val resume = NowPlayingWidgetState.Resume("b1", "The Book You Were Listening To", "A. Writer", 42, "5 h 48 min left")

    private val controls = ListenWidgetState.Controls(
        "b1", "A Reasonably Titled Book", "A. Writer", "Chapter 1. The Beginning", 42,
        isPlaying = true, canSkip = true, canJumpChapters = true,
    )
    private val controlsLong = controls.copy(
        title = "An Extraordinarily Long Audiobook Title That Cannot Possibly Fit",
        chapterTitle = "Chapter 12. A Chapter Name That Is Far Too Long", isPlaying = false,
    )
    private val listenResume = ListenWidgetState.Resume("b1", "The Book You Were Listening To", "A. Writer", 42, "5 h 48 min left")
    private val continueBook = ContinueWidgetState.Book("b1", "The Book You Were Listening To", "A. Writer", 42, "5 h 48 min left")

    private val cover: Bitmap by lazy {
        Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).also { bmp ->
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, 120f, 120f, Color.rgb(120, 40, 90), Color.rgb(30, 60, 110), Shader.TileMode.CLAMP)
            }
            Canvas(bmp).drawRect(0f, 0f, 120f, 120f, paint)
        }
    }

    // ---- Now Playing ----
    @Test fun nowPlayingNarrow() = shot("widget_np_narrow_dark", 40f, 40f, ThemeMode.DARK) { c, k -> NowPlayingContent(c, book, null, k) }
    @Test fun nowPlayingWide() = shot("widget_np_wide_dark", 180f, 40f, ThemeMode.DARK) { c, k -> NowPlayingContent(c, book, null, k) }
    @Test fun nowPlayingCover() = shot("widget_np_cover_dark", 250f, 56f, ThemeMode.DARK) { c, k -> NowPlayingContent(c, book, cover, k) }
    @Test fun nowPlayingCoverLight() = shot("widget_np_cover_light", 250f, 56f, ThemeMode.LIGHT) { c, k -> NowPlayingContent(c, book, cover, k) }
    @Test fun nowPlayingLongTitle() = shot("widget_np_long_title", 250f, 56f, ThemeMode.DARK) { c, k -> NowPlayingContent(c, longBook, cover, k) }
    @Test fun nowPlayingResume() = shot("widget_np_resume", 250f, 56f, ThemeMode.DARK) { c, k -> NowPlayingContent(c, resume, cover, k) }
    @Test fun nowPlayingEmpty() = shot("widget_np_empty", 250f, 56f, ThemeMode.DARK) { c, k -> NowPlayingContent(c, NowPlayingWidgetState.Empty(everPlayed = false), null, k) }

    // ---- Continue Listening ----
    @Test fun continueBook() = shot("widget_continue_dark", 250f, 110f, ThemeMode.DARK) { c, k -> ContinueContent(c, continueBook, cover, k) }
    @Test fun continueBookLight() = shot("widget_continue_light", 250f, 110f, ThemeMode.LIGHT) { c, k -> ContinueContent(c, continueBook, cover, k) }
    @Test fun continueEmpty() = shot("widget_continue_empty", 250f, 110f, ThemeMode.DARK) { c, k -> ContinueContent(c, ContinueWidgetState.Empty, null, k) }

    // ---- Listen: every size bucket ----
    @Test fun listenBuckets() {
        for (bucket in LISTEN_BUCKETS) {
            shot("widget_listen_${bucket.name.lowercase()}_dark", bucket.widthDp, bucket.heightDp, ThemeMode.DARK) { c, k ->
                ListenContent(c, controls, cover, k)
            }
        }
    }

    @Test fun listenBucketsLight() {
        for (name in listOf("BAR", "WIDE_MID", "LARGE_ROOMY")) {
            val bucket = LISTEN_BUCKETS.first { it.name == name }
            shot("widget_listen_${name.lowercase()}_light", bucket.widthDp, bucket.heightDp, ThemeMode.LIGHT) { c, k ->
                ListenContent(c, controls, cover, k)
            }
        }
    }

    @Test fun listenLongTitles() {
        for (name in listOf("BAR", "WIDE_MID", "LARGE_ROOMY")) {
            val bucket = LISTEN_BUCKETS.first { it.name == name }
            shot("widget_listen_${name.lowercase()}_long", bucket.widthDp, bucket.heightDp, ThemeMode.DARK) { c, k ->
                ListenContent(c, controlsLong, cover, k)
            }
        }
    }

    @Test fun listenResumeAndEmpty() {
        val wide = LISTEN_BUCKETS.first { it.name == "WIDE_MID" }
        shot("widget_listen_resume", wide.widthDp, wide.heightDp, ThemeMode.DARK) { c, k -> ListenContent(c, listenResume, cover, k) }
        shot("widget_listen_empty", wide.widthDp, wide.heightDp, ThemeMode.DARK) { c, k ->
            ListenContent(c, ListenWidgetState.Empty(everPlayed = false), null, k)
        }
    }

    /** Composes [content] at exactly [widthDp] x [heightDp], inflates the RemoteViews and captures them on a wallpaper-grey page. */
    private fun shot(name: String, widthDp: Float, heightDp: Float, mode: ThemeMode, content: @Composable (Context, WidgetColors) -> Unit) {
        val widget = object : GlanceAppWidget() {
            override val sizeMode = SizeMode.Exact
            override suspend fun provideGlance(context: Context, id: GlanceId) {
                provideContent { content(context, widgetColors(mode, context)) }
            }
        }
        val views = runBlocking { widget.compose(context, AppWidgetId(1), Bundle(), DpSize(widthDp.dp, heightDp.dp)) }
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        val page = FrameLayout(context).apply { setBackgroundColor(Color.rgb(70, 74, 84)) }
        val widgetView = views.apply(context, page)
        page.addView(widgetView, FrameLayout.LayoutParams(w, h).apply { setMargins(12, 12, 12, 12) })
        page.measure(
            View.MeasureSpec.makeMeasureSpec(w + 24, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h + 24, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, w + 24, h + 24)
        // Roborazzi's View capture wants an Activity; the RemoteViews are drawn into a bitmap here instead.
        val image = Bitmap.createBitmap(w + 24, h + 24, Bitmap.Config.ARGB_8888)
        page.draw(Canvas(image))
        image.captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
