package com.chapterplayer.domain.browse

private val WHITESPACE = Regex("\\s+")

fun searchItems(items: List<BrowseItem>, query: String): List<BrowseItem> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return emptyList()
    
    val words = trimmedQuery.split(WHITESPACE)
    
    // A merged book matches on any member's details (the servers may spell them differently).
    return items.filter { item ->
        val fields = item.allSources.flatMap { member ->
            val book = member.book
            listOfNotNull(book.title, book.author, book.narrator, book.seriesName)
        }
        
        words.all { word ->
            fields.any { field ->
                field.contains(word, ignoreCase = true)
            }
        }
    }
}
