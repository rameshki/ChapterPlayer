package com.chapterplayer.domain.browse

data class ParsedSeries(val name: String, val sequence: String?)

private val sequenceAtEnd = Regex("^(.*?)\\s*#\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$")
private val sequenceThenComma = Regex("#\\s*[0-9]+(?:\\.[0-9]+)?\\s*,\\s")

/**
 * Parses Audiobookshelf's `seriesName`, e.g. "Saga #3". Audiobookshelf joins every series of a book
 * with ", " ("Saga A #1, Saga B #2"); a book belongs to its first series only, so the text is cut at
 * the first ", " that directly follows a `#<number>`. A comma inside a name without a number stays.
 */
fun parseSeriesName(raw: String?): ParsedSeries? {
    if (raw.isNullOrBlank()) return null

    val trimmed = raw.trim()
    val firstSeries = sequenceThenComma.find(trimmed)
        ?.let { trimmed.substring(0, it.range.last - 1).trim() }
        ?: trimmed

    val match = sequenceAtEnd.find(firstSeries) ?: return ParsedSeries(firstSeries, null)

    val namePart = match.groupValues[1]
    val sequencePart = match.groupValues[2]

    // If name part is empty after trimming, return the whole string as name
    if (namePart.isBlank()) return ParsedSeries(firstSeries, null)

    return ParsedSeries(namePart.trim(), sequencePart)
}
