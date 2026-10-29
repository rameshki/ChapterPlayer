package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.BookCollection

private val SEPARATORS = Regex("[,;&]")
private val SPACES = Regex("\\s+")

data class BookGroup(val key: String, val title: String, val items: List<BrowseItem>)

fun groupByAuthor(items: List<BrowseItem>): List<BookGroup> {
    return groupPeople(items, "Unknown author") { it.book.author }
}

fun groupByNarrator(items: List<BrowseItem>): List<BookGroup> {
    return groupPeople(items, "Unknown narrator") { it.book.narrator }
}

private fun groupPeople(
    items: List<BrowseItem>,
    unknownTitle: String,
    nameOf: (BrowseItem) -> String
): List<BookGroup> {
    val groups = LinkedHashMap<String, MutableList<BrowseItem>>()
    val titles = mutableMapOf<String, String>()
    
    for (item in items) {
        val names = splitPeople(nameOf(item))
        if (names.isEmpty()) {
            val key = "unknown"
            groups.getOrPut(key) { mutableListOf() }.add(item)
            if (!titles.containsKey(key)) {
                titles[key] = unknownTitle
            }
        } else {
            val seenKeys = HashSet<String>()
            for (name in names) {
                val key = name.lowercase()
                if (key !in seenKeys) {
                    seenKeys.add(key)
                    groups.getOrPut(key) { mutableListOf() }.add(item)
                    if (!titles.containsKey(key)) {
                        titles[key] = name
                    }
                }
            }
        }
    }
    
    val sortedGroups = groups.map { (key, itemsList) ->
        BookGroup(key, titles[key]!!, itemsList)
    }.sortedWith(compareBy<BookGroup>({ personSortKey(it.title) }, { it.key }))
    
    val (unknown, others) = sortedGroups.partition { it.key == "unknown" }
    return others + unknown
}

fun groupBySeries(items: List<BrowseItem>): List<BookGroup> {
    val groups = mutableMapOf<String, MutableList<BrowseItem>>()
    val titles = mutableMapOf<String, String>()
    
    for (item in items) {
        val seriesName = item.book.seriesName
        if (!seriesName.isNullOrBlank()) {
            val key = seriesName.trim().lowercase()
            groups.getOrPut(key) { mutableListOf() }.add(item)
            if (!titles.containsKey(key)) {
                titles[key] = seriesName.trim()
            }
        }
    }
    
    fun seq(it: BrowseItem) = it.book.seriesSequence?.toDoubleOrNull()?.takeIf { it.isFinite() }
    
    for (itemsList in groups.values) {
        itemsList.sortWith(compareBy<BrowseItem>(
            { seq(it) == null },
            { seq(it) ?: 0.0 },
            { titleSortKey(it.book.title) },
            { it.book.id }
        ))
    }
    
    return groups.map { (key, itemsList) ->
        BookGroup(key, titles[key]!!, itemsList)
    }.sortedWith(compareBy<BookGroup>(
        { titleSortKey(it.title) },
        { it.key }
    ))
}

fun groupByCollection(items: List<BrowseItem>, collections: List<BookCollection>): List<BookGroup> {
    // A merged book is in a server's collection when its member on that server is.
    val byServer = HashMap<String, HashMap<String, BrowseItem>>()
    for (item in items) for (member in item.allSources) {
        val m = byServer.getOrPut(member.book.serverId) { HashMap() }
        if (member.book.id !in m) m[member.book.id] = item
    }
    
    val groups = mutableListOf<BookGroup>()
    
    for (collection in collections) {
        val lookup = byServer[collection.serverId] ?: continue
        val collectionItems = collection.bookIds.distinct().mapNotNull { lookup[it] }
        
        if (collectionItems.isNotEmpty()) {
            groups.add(BookGroup("${collection.serverId}/${collection.id}", collection.name, collectionItems))
        }
    }
    
    return groups
}

internal fun splitPeople(name: String): List<String> {
    if (name.isBlank()) {
        return listOf()
    }

    val result = mutableListOf<String>()
    var start = 0
    var depth = 0
    val chars = name.toCharArray()

    for (i in chars.indices) {
        when (chars[i]) {
            '(', '[' -> depth++
            ')', ']' -> {
                depth--
                if (depth < 0) depth = 0
            }
            ',', ';', '&' -> {
                if (depth == 0) {
                    val part = name.substring(start, i).trim().replace(SPACES, " ")
                    if (part.isNotEmpty()) {
                        result.add(part)
                    }
                    start = i + 1
                }
            }
        }
    }

    // Add the last part
    val lastPart = name.substring(start).trim().replace(SPACES, " ")
    if (lastPart.isNotEmpty()) {
        result.add(lastPart)
    }

    return result
}
