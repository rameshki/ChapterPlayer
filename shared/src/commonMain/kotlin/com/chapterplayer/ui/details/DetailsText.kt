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
