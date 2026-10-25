package com.chapterplayer.ui.widgets

import android.content.Context
import android.content.Intent
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
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.playback.EXTRA_OPEN_PLAYER
import com.chapterplayer.shared.R
import com.chapterplayer.ui.theme.ChapterPlayerPalette
import com.chapterplayer.ui.theme.DarkPalette
import com.chapterplayer.ui.theme.LightPalette

/** The app palette's colours as the widgets use them; they follow the app's theme setting. */
internal class WidgetColors(
    val background: ColorProvider,
    val textPrimary: ColorProvider,
    val textSecondary: ColorProvider,
    val accent: ColorProvider,
    val onAccent: ColorProvider,
)

internal fun widgetColors(mode: ThemeMode): WidgetColors = when (mode) {
    ThemeMode.DARK -> fixed(DarkPalette)
    ThemeMode.LIGHT -> fixed(LightPalette)
    // "Follow system": the launcher picks day or night as the system changes, without a redraw.
    ThemeMode.SYSTEM -> WidgetColors(
        background = ColorProvider(day = LightPalette.surfaceElevated, night = DarkPalette.surfaceElevated),
        textPrimary = ColorProvider(day = LightPalette.textPrimary, night = DarkPalette.textPrimary),
        textSecondary = ColorProvider(day = LightPalette.textSecondary, night = DarkPalette.textSecondary),
        accent = ColorProvider(day = LightPalette.amber, night = DarkPalette.amber),
        onAccent = ColorProvider(day = LightPalette.onAmber, night = DarkPalette.onAmber),
    )
}

private fun fixed(p: ChapterPlayerPalette) = WidgetColors(
    background = ColorProvider(p.surfaceElevated),
    textPrimary = ColorProvider(p.textPrimary),
    textSecondary = ColorProvider(p.textSecondary),
    accent = ColorProvider(p.amber),
    onAccent = ColorProvider(p.onAmber),
)

/**
 * The app's launch intent; with [openPlayer] it opens on the full player, as the playback notification does.
 * [extras] carry anything else MainActivity reads (the Continue widget's book).
 */
internal fun openAppIntent(context: Context, openPlayer: Boolean, extras: Map<String, String> = emptyMap()): Intent? =
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        if (openPlayer) putExtra(EXTRA_OPEN_PLAYER, true)
        extras.forEach { (key, value) -> putExtra(key, value) }
    }

/** A tinted icon button; the touch target stays at least 40 dp. */
@Composable
internal fun WidgetIconButton(icon: Int, description: String, tint: ColorProvider, onClick: Action, size: Dp = 40.dp, label: String? = null) {
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

/** The neutral cover: widgets never fetch covers (that needs the server token). */
@Composable
internal fun WidgetCoverPlaceholder(tint: ColorProvider, size: Dp = 32.dp) {
    Image(
        provider = ImageProvider(R.drawable.ic_widget_book),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier.size(size),
    )
}
