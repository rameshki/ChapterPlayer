package com.chapterplayer.domain.widgets

/*
 * The look the three home screen widgets share (a design reference, not code: a rounded charcoal card, a rounded cover,
 * white text and a round white play button; the light theme reverses it). Sizes here, colours in ui/theme/WidgetPalette.
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
