package com.chapterplayer.domain.widgets

import com.chapterplayer.data.settings.ThemeMode

/*
 * The look the three home screen widgets share (the layout follows a design reference, not its code: a rounded card, a
 * rounded cover and a round play button; the colours are the app's midnight theme). Sizes here, colours in ui/theme/WidgetPalette.
 */

/** Sizes every widget uses. */
object WidgetDimens {
    /** The card's corner radius. */
    const val CARD_RADIUS_DP = 24f

    /** A cover's corner radius (a rounded square). */
    const val COVER_RADIUS_DP = 12f

    /** Between the cover and the text. */
    const val GAP_DP = 8f

    /** Back 15, forward 30 and the chapter buttons. */
    const val SIDE_BUTTON_DP = 40f

    /** The round play button's touch target where the widget is tall enough. */
    const val PLAY_TARGET_DP = 48f

    /** The white circle inside the play button's target, as a share of the target. */
    const val PLAY_CIRCLE_SHARE = 0.86f

    // One-row widgets (Now Playing, Continue Listening): a row can be as low as 40 dp.
    const val ROW_PADDING_DP = 8f
    const val ROW_COVER_DP = 36f
    const val ROW_PLAY_DP = 44f

    /** A one-row widget keeps at least this much for its title. */
    const val ROW_TEXT_MIN_DP = 36f
}

/** From this width Now Playing shows the loaded book's cover before its title. */
const val NOW_PLAYING_COVER_MIN_DP = 250f

/** Continue Listening's smallest width (its provider's minimum). */
const val CONTINUE_MIN_WIDTH_DP = 180f

fun nowPlayingShowsCover(widthDp: Float): Boolean = widthDp >= NOW_PLAYING_COVER_MIN_DP

/** Which card drawable a widget draws on. */
enum class WidgetCard { DARK, LIGHT, SYSTEM }

/** From this Android version (12, API 31) the launcher resolves day / night colours itself. */
const val WIDGET_NIGHT_COLOURS_SDK = 31

/**
 * The card for the theme [mode]. "Follow system" uses the day / night card the launcher picks from Android 12; before
 * that Glance bakes the text and button colours with the app's own night mode ([systemNight]), so the card is baked from
 * the same one (a launcher-picked card could turn dark under dark text).
 */
fun widgetCard(mode: ThemeMode, sdkInt: Int, systemNight: Boolean): WidgetCard = when (mode) {
    ThemeMode.DARK -> WidgetCard.DARK
    ThemeMode.LIGHT -> WidgetCard.LIGHT
    ThemeMode.SYSTEM -> when {
        sdkInt >= WIDGET_NIGHT_COLOURS_SDK -> WidgetCard.SYSTEM
        systemNight -> WidgetCard.DARK
        else -> WidgetCard.LIGHT
    }
}

/** Before Android 12 the baked colours are stale after a day / night switch: the widgets are drawn again then. */
fun widgetsRedrawOnNightChange(sdkInt: Int): Boolean = sdkInt < WIDGET_NIGHT_COLOURS_SDK
