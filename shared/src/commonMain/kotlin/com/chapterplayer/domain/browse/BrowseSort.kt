package com.chapterplayer.domain.browse

enum class BrowseSort { RECENTLY_ADDED, RECENTLY_PLAYED, TITLE, AUTHOR, LENGTH }

fun sortItems(items: List<BrowseItem>, sort: BrowseSort): List<BrowseItem> {
    return when (sort) {
        BrowseSort.RECENTLY_ADDED -> items.sortedWith(compareByDescending<BrowseItem> { it.book.addedAt }
            .thenBy<BrowseItem> { titleSortKey(it.book.title) }
            .thenBy<BrowseItem> { it.book.id })
        BrowseSort.RECENTLY_PLAYED -> items.sortedWith(compareByDescending<BrowseItem> { 
            it.progress?.lastPlayedAt ?: Long.MIN_VALUE } 
            .thenBy<BrowseItem> { titleSortKey(it.book.title) }
            .thenBy<BrowseItem> { it.book.id })
        BrowseSort.TITLE -> items.sortedWith(compareBy<BrowseItem> { 
            titleSortKey(it.book.title) }
            .thenBy<BrowseItem> { it.book.id })
        BrowseSort.AUTHOR -> items.sortedWith(compareBy<BrowseItem> { 
            it.book.author.lowercase() }
            .thenBy<BrowseItem> { titleSortKey(it.book.title) }
            .thenBy<BrowseItem> { it.book.id })
        BrowseSort.LENGTH -> items.sortedWith(compareByDescending<BrowseItem> { it.book.durationMs }
            .thenBy<BrowseItem> { titleSortKey(it.book.title) }
            .thenBy<BrowseItem> { it.book.id })
    }
}

internal fun titleSortKey(title: String): String {
    val cleaned = title.lowercase().trim()
    return when {
        cleaned.startsWith("the ") -> cleaned.substring(4)
        cleaned.startsWith("a ") -> cleaned.substring(2)
        cleaned.startsWith("an ") -> cleaned.substring(3)
        else -> cleaned
    }
}
