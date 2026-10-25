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
            personSortKey(it.book.author) }
            .thenBy<BrowseItem> { titleSortKey(it.book.title) }
            .thenBy<BrowseItem> { it.book.id })
        BrowseSort.LENGTH -> items.sortedWith(compareByDescending<BrowseItem> { it.book.durationMs }
            .thenBy<BrowseItem> { titleSortKey(it.book.title) }
            .thenBy<BrowseItem> { it.book.id })
    }
}

