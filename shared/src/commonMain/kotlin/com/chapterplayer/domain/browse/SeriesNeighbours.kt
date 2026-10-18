package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook

/**
 * Represents a book within a series alongside its relationship to the currently viewed book.
 */
data class SeriesEntry(val item: BrowseItem, val isCurrent: Boolean)

/**
 * Returns a list of items belonging to the same series as the current book, ordered correctly.
 */
fun seriesNeighbours(all: List<BrowseItem>, current: AudioBook): List<SeriesEntry> {
    val currentSeries = current.seriesName?.trim()
    if (currentSeries.isNullOrBlank()) return emptyList()

    // Filter for items in the same series on the same server
    val matchingItems = all.filter { item ->
        val book = item.book
        val isSameServer = book.serverId == current.serverId
        val isSameSeries = book.seriesName?.trim().equals(currentSeries, ignoreCase = true)
        isSameServer && isSameSeries
    }

    // Use existing grouping logic to handle complex sorting rules
    val groups = groupBySeries(matchingItems)
    val seriesGroup = groups.find { it.key == currentSeries.lowercase() }
    val orderedItems = seriesGroup?.items ?: emptyList()

    return orderedItems.map { item ->
        // Determine if this specific item is the one currently being read
        val isCurrentItem = item.book.id == current.id && item.book.serverId == current.serverId
        SeriesEntry(item, isCurrentItem)
    }
}
