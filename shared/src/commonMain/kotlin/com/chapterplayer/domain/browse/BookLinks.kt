package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState

/**
 * Represents metadata about a series related to a book.
 */
data class SeriesLink(val key: String, val title: String, val sequence: String?, val bookCount: Int)

/**
 * Represents metadata about a person (author or narrator) related to a book.
 */
data class PersonLink(val key: String, val title: String, val otherBookCount: Int)

/**
 * A container for all relational links available for a book.
 */
data class BookLinks(val series: SeriesLink?, val authors: List<PersonLink>, val narrators: List<PersonLink>)

/**
 * Calculates relevant series, author, and narrator links for a specific book based on a collection of items.
 */
fun bookLinks(all: List<BrowseItem>, current: AudioBook): BookLinks {
    val exists = all.any { it.book.id == current.id && it.book.serverId == current.serverId }
    val items = if (exists) all else all + BrowseItem(current, null, DownloadState.NOT_DOWNLOADED)

    val seriesLink = if (current.seriesName.isNullOrBlank()) {
        null
    } else {
        val key = current.seriesName.trim().lowercase()
        groupBySeries(items).find { it.key == key }?.let { group ->
            SeriesLink(group.key, group.title, current.seriesSequence, group.items.size)
        }
    }

    val authors = getPersonLinks(groupByAuthor(items), current)
    val narrators = getPersonLinks(groupByNarrator(items), current)

    return BookLinks(seriesLink, authors, narrators)
}

/**
 * Internal helper to transform book groups into person links for authors or narrators.
 */
private fun getPersonLinks(groups: List<BookGroup>, current: AudioBook): List<PersonLink> {
    return groups
        .filter { it.key != "unknown" && it.items.any { item -> item.book.id == current.id && item.book.serverId == current.serverId } }
        .map { group ->
            val otherCount = group.items.size - 1
            PersonLink(group.key, group.title, otherCount)
        }
        .filter { it.otherBookCount > 0 }
        .sortedBy { it.title.lowercase() }
}
