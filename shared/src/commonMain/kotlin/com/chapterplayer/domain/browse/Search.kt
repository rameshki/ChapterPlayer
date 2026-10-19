package com.chapterplayer.domain.browse

private val WHITESPACE = Regex("\\s+")

fun searchItems(items: List<BrowseItem>, query: String): List<BrowseItem> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return emptyList()
    
    val words = trimmedQuery.split(WHITESPACE)
    
    return items.filter { item ->
        val book = item.book
        val fields = listOfNotNull(
            book.title,
            book.author,
            book.narrator,
            book.seriesName
        )
        
        words.all { word ->
            fields.any { field ->
                field.contains(word, ignoreCase = true)
            }
        }
    }
}
