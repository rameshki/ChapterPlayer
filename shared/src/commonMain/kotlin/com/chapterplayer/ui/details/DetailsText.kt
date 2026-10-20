package com.chapterplayer.ui.details

import com.chapterplayer.domain.browse.PersonLink
import com.chapterplayer.domain.browse.SeriesLink

internal fun bookCountText(count: Int): String = if (count == 1) "1 book" else "$count books"

/** "Part of Saga #2"; the number is left out when the book has none. */
internal fun seriesLineText(link: SeriesLink): String {
    val sequence = link.sequence?.trim().orEmpty()
    return if (sequence.isEmpty()) "Part of ${link.title}" else "Part of ${link.title} #$sequence"
}

/** "More by Amy Author · 3 books", or "More by Nia Narrator as narrator · 1 book". */
internal fun moreByText(person: PersonLink, asNarrator: Boolean): String {
    val role = if (asNarrator) " as narrator" else ""
    return "More by ${person.title}$role · ${bookCountText(person.otherBookCount)}"
}

/** The tabs to show: Series only when the book has neighbours. */
internal fun visibleTabs(hasSeries: Boolean): List<DetailsTab> =
    if (hasSeries) listOf(DetailsTab.ABOUT, DetailsTab.CHAPTERS, DetailsTab.SERIES)
    else listOf(DetailsTab.ABOUT, DetailsTab.CHAPTERS)

/** The tab to draw: a remembered Series tab falls back to Chapters when the series is gone. */
internal fun effectiveTab(selected: DetailsTab, hasSeries: Boolean): DetailsTab =
    if (selected in visibleTabs(hasSeries)) selected else DetailsTab.CHAPTERS

internal fun tabLabel(tab: DetailsTab): String = when (tab) {
    DetailsTab.ABOUT -> "About"
    DetailsTab.CHAPTERS -> "Chapters"
    DetailsTab.SERIES -> "Series"
}

/** The length and time-left line under the author, or null when there is nothing to say. */
internal fun lengthLine(lengthText: String, timeLeft: String?): String? =
    listOfNotNull(lengthText.takeIf { it.isNotBlank() }, timeLeft).joinToString(" · ").ifEmpty { null }

internal const val NO_CHAPTERS_TEXT = "No chapters in this book"
internal const val LOADING_CHAPTERS_TEXT = "Loading chapters..."
internal const val CHAPTERS_NEED_CONNECTION_TEXT = "Chapters need a connection"

/**
 * What an empty chapter list says: "none" only once the server answered, "loading" while it is asked,
 * and "need a connection" when nothing was cached and the call failed or never ran.
 */
internal fun emptyChaptersText(detailsLoaded: Boolean, loadingDetails: Boolean): String = when {
    detailsLoaded -> NO_CHAPTERS_TEXT
    loadingDetails -> LOADING_CHAPTERS_TEXT
    else -> CHAPTERS_NEED_CONNECTION_TEXT
}

internal const val MORE_LINKS_LIMIT = 3

/** The links to draw: the first [limit] unless [expanded]. */
internal fun <T> cappedLinks(links: List<T>, expanded: Boolean, limit: Int = MORE_LINKS_LIMIT): List<T> =
    if (expanded || links.size <= limit) links else links.take(limit)

/** The toggle under a capped list, or null when everything already fits. */
internal fun linksToggleLabel(total: Int, expanded: Boolean, limit: Int = MORE_LINKS_LIMIT): String? = when {
    total <= limit -> null
    expanded -> "Show less"
    else -> "Show all ($total)"
}

internal const val NEEDS_CONNECTION_TEXT = "Needs a connection to play"
internal const val CHAPTERS_TAP_NOTE = "Tap a chapter to jump to it"

/** The play button's label: Resume when there is somewhere to resume from, Play for a fresh book. */
internal fun playButtonLabel(playback: DetailsPlayback, hasProgress: Boolean): String = when (playback) {
    DetailsPlayback.PLAYING -> "Pause"
    DetailsPlayback.PAUSED -> "Resume"
    DetailsPlayback.NOT_PLAYING, DetailsPlayback.STARTING -> if (hasProgress) "Resume" else "Play"
}

/** Pausing is always possible; starting needs [canPlay]; nothing happens while a start is under way. */
internal fun playButtonEnabled(playback: DetailsPlayback, canPlay: Boolean): Boolean = when (playback) {
    DetailsPlayback.PLAYING -> true
    DetailsPlayback.STARTING -> false
    DetailsPlayback.NOT_PLAYING, DetailsPlayback.PAUSED -> canPlay
}

/** The line under the play button: why it is disabled, or nothing. */
internal fun playCaption(playback: DetailsPlayback, canPlay: Boolean, reason: String?): String? =
    if (canPlay || playback == DetailsPlayback.PLAYING) null else reason

/** Chapter rows can be tapped while this book is loaded, or when it can be started. */
internal fun chapterTapsEnabled(playback: DetailsPlayback, canPlay: Boolean): Boolean = when (playback) {
    DetailsPlayback.PLAYING, DetailsPlayback.PAUSED -> true
    DetailsPlayback.NOT_PLAYING -> canPlay
    DetailsPlayback.STARTING -> false
}

/** The note above the chapter list; hidden when the rows cannot be tapped. */
internal fun chaptersNote(tapsEnabled: Boolean): String? = if (tapsEnabled) CHAPTERS_TAP_NOTE else null

/** The details-load error to show: none while a play error is showing, so only one error banner appears. */
internal fun detailsErrorToShow(playError: String?, loadError: String?): String? =
    if (playError != null) null else loadError
