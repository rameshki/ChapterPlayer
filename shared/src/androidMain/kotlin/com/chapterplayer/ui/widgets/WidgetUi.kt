package com.chapterplayer.ui.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.playback.EXTRA_OPEN_PLAYER
import com.chapterplayer.domain.widgets.WidgetCard
import com.chapterplayer.domain.widgets.WidgetDimens
import com.chapterplayer.domain.widgets.widgetCard
import com.chapterplayer.shared.R
import com.chapterplayer.ui.theme.WidgetDarkPalette
import com.chapterplayer.ui.theme.WidgetLightPalette
import com.chapterplayer.ui.theme.WidgetPalette

/**
 * The widgets' shared look (ui/theme/WidgetPalette, domain/widgets/WidgetLook): a rounded card with a vertical
 * gradient, white text and a round white play button in the dark theme, reversed in the light one. They follow the
 * app's theme setting; "follow system" lets the launcher pick day or night without a redraw.
 */
internal class WidgetColors(
    /** The card: a gradient drawable with the card's rounded corners. */
    val card: Int,
    val textPrimary: ColorProvider,
    val textSecondary: ColorProvider,
    val icon: ColorProvider,
    val playFill: ColorProvider,
    val playGlyph: ColorProvider,
    val progress: ColorProvider,
    val track: ColorProvider,
    val placeholder: ColorProvider,
)

/**
 * The colours for the theme [mode] ([widgetCard]): "follow system" lets the launcher pick day or night from Android 12;
 * before that Glance bakes the colours with [context]'s night mode, so the card is chosen from the same one.
 */
internal fun widgetColors(mode: ThemeMode, context: Context): WidgetColors =
    when (widgetCard(mode, Build.VERSION.SDK_INT, GlanceWidgetHost.isNight(context.resources.configuration))) {
    WidgetCard.DARK -> fixed(WidgetDarkPalette, R.drawable.widget_card_dark)
    WidgetCard.LIGHT -> fixed(WidgetLightPalette, R.drawable.widget_card_light)
    // Android 12+: drawable-night and day/night colours, picked by the launcher.
    WidgetCard.SYSTEM -> {
        val day = WidgetLightPalette
        val night = WidgetDarkPalette
        WidgetColors(
            card = R.drawable.widget_card_system,
            textPrimary = ColorProvider(day = day.textPrimary, night = night.textPrimary),
            textSecondary = ColorProvider(day = day.textSecondary, night = night.textSecondary),
            icon = ColorProvider(day = day.icon, night = night.icon),
            playFill = ColorProvider(day = day.playFill, night = night.playFill),
            playGlyph = ColorProvider(day = day.playGlyph, night = night.playGlyph),
            progress = ColorProvider(day = day.progress, night = night.progress),
            track = ColorProvider(day = day.track, night = night.track),
            placeholder = ColorProvider(day = day.placeholder, night = night.placeholder),
        )
    }
}

private fun fixed(p: WidgetPalette, card: Int) = WidgetColors(
    card = card,
    textPrimary = ColorProvider(p.textPrimary),
    textSecondary = ColorProvider(p.textSecondary),
    icon = ColorProvider(p.icon),
    playFill = ColorProvider(p.playFill),
    playGlyph = ColorProvider(p.playGlyph),
    progress = ColorProvider(p.progress),
    track = ColorProvider(p.track),
    placeholder = ColorProvider(p.placeholder),
)

/** The card every widget draws on: the gradient drawable (rounded itself before Android 12), clipped on 12+. */
internal fun cardModifier(colors: WidgetColors): GlanceModifier =
    GlanceModifier.fillMaxSize()
        .background(ImageProvider(colors.card), ContentScale.FillBounds)
        .cornerRadius(WidgetDimens.CARD_RADIUS_DP.dp)

/**
 * The app's launch intent; with [openPlayer] it opens on the full player, as the playback notification does.
 * [extras] carry anything else MainActivity reads (the Continue widget's book).
 */
internal fun openAppIntent(context: Context, openPlayer: Boolean, extras: Map<String, String> = emptyMap()): Intent? =
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        if (openPlayer) putExtra(EXTRA_OPEN_PLAYER, true)
        extras.forEach { (key, value) -> putExtra(key, value) }
    }

/** A tinted icon button (back 15, forward 30, chapters); the touch target stays at least 40 dp. */
@Composable
internal fun WidgetIconButton(icon: Int, description: String, tint: ColorProvider, onClick: Action, size: Dp = WidgetDimens.SIDE_BUTTON_DP.dp, label: String? = null) {
    Box(
        modifier = GlanceModifier.size(size).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(size - 12.dp),
        )
        if (label != null) {
            Text(
                text = label,
                style = TextStyle(color = tint, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.padding(top = 3.dp),
            )
        }
    }
}

/** The round play / pause button: a filled circle with the glyph cut out in the card's colour. [target] is the touch area. */
@Composable
internal fun WidgetPlayButton(pause: Boolean, description: String, onClick: Action, colors: WidgetColors, target: Dp = WidgetDimens.PLAY_TARGET_DP.dp) {
    val circle = target * WidgetDimens.PLAY_CIRCLE_SHARE
    Box(modifier = GlanceModifier.size(target).clickable(onClick), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(R.drawable.widget_play_circle),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.playFill),
            modifier = GlanceModifier.size(circle),
        )
        Image(
            provider = ImageProvider(if (pause) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            contentDescription = description,
            colorFilter = ColorFilter.tint(colors.playGlyph),
            modifier = GlanceModifier.size(circle * 0.55f),
        )
    }
}

/**
 * A book's cover as a rounded square of [size]: the bitmap the app loaded (never an address), else the placeholder (a
 * rounded square with the book symbol). Decorative: the title beside it says what it is.
 */
@Composable
internal fun WidgetCover(cover: Bitmap?, size: Dp, colors: WidgetColors) {
    val radius = WidgetDimens.COVER_RADIUS_DP.dp
    if (cover != null) {
        Image(
            provider = ImageProvider(cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.size(size).cornerRadius(radius),
        )
    } else {
        Box(modifier = GlanceModifier.size(size).background(colors.placeholder).cornerRadius(radius), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_book),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.textSecondary),
                modifier = GlanceModifier.size(size * 0.5f),
            )
        }
    }
}

/** The thin progress bar (white on grey in the dark theme). Before Android 12 Glance ignores the colours (system tint). */
@Composable
internal fun WidgetProgressBar(percent: Int, colors: WidgetColors, modifier: GlanceModifier) {
    LinearProgressIndicator(
        progress = percent.coerceIn(0, 100) / 100f,
        modifier = modifier.height(4.dp).cornerRadius(2.dp),
        color = colors.progress,
        backgroundColor = colors.track,
    )
}
