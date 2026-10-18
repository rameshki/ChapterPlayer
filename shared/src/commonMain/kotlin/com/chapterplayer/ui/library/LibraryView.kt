package com.chapterplayer.ui.library

import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.domain.browse.BookGroup
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.groupByAuthor
import com.chapterplayer.domain.browse.groupByCollection
import com.chapterplayer.domain.browse.groupByNarrator
import com.chapterplayer.domain.browse.groupBySeries

/** The five views along the top of the Library screen. */
enum class LibraryView(val title: String, val singularTitle: String) {
    LIBRARY("Library", "Book"),
    AUTHORS("Authors", "Author"),
    NARRATORS("Narrators", "Narrator"),
    SERIES("Series", "Series"),
    COLLECTIONS("Collections", "Collection");

    /** Whether this view lists groups (each opens a detail screen) rather than books. */
    val isGrouped: Boolean get() = this != LIBRARY
}

/** The groups of a grouped [view] made from the books that passed the filters; empty for [LibraryView.LIBRARY]. */
fun groupsFor(view: LibraryView, items: List<BrowseItem>, collections: List<BookCollection>): List<BookGroup> =
    when (view) {
        LibraryView.LIBRARY -> emptyList()
        LibraryView.AUTHORS -> groupByAuthor(items)
        LibraryView.NARRATORS -> groupByNarrator(items)
        LibraryView.SERIES -> groupBySeries(items)
        LibraryView.COLLECTIONS -> groupByCollection(items, collections)
    }

/**
 * A group key as it travels in a navigation route. Keys hold names with spaces, slashes and symbols, and
 * different navigation stacks decode them differently, so the key goes as hex of its UTF-8 bytes, which
 * no decoding changes.
 */
fun encodeGroupKey(key: String): String =
    key.encodeToByteArray().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** The key [encodeGroupKey] made, or null when [encoded] is not one of its results. */
fun decodeGroupKey(encoded: String): String? {
    if (encoded.length % 2 != 0) return null
    val bytes = ByteArray(encoded.length / 2)
    for (i in bytes.indices) {
        bytes[i] = (encoded.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
    }
    return bytes.decodeToString()
}
