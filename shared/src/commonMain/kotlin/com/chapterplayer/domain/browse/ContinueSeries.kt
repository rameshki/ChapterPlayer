package com.chapterplayer.domain.browse

/**
 * Returns a list of candidate books to continue a series, suggesting the next unplayed book 
 * in a series after the most advanced book in that series has been completed.
 * 
 * @param items The list of all available browse items.
 * @param excludeBookId An optional book ID to be removed from the resulting list.
 * @return A list of items representing the next book to read in various series, ordered by recent activity.
 */
fun continueSeries(items: List<BrowseItem>, excludeBookId: String? = null): List<BrowseItem> {
    // Group by serverId to ensure series are never merged across different servers
    val itemsByServer = items.groupBy { it.book.serverId }
    val candidates = mutableListOf<Pair<BrowseItem, Long>>()

    for (serverItems in itemsByServer.values) {
        val groups = groupBySeries(serverItems)
        for (group in groups) {
            val groupItems = group.items
            
            // Determine which books have been "touched" (played or finished)
            val touchedIndices = groupItems.indices.filter { i ->
                val p = groupItems[i].progress
                p != null && (p.isFinished || p.positionMs > 0)
            }

            if (touchedIndices.isEmpty()) continue

            // Find the last touched book in the sequence
            val lastTouchedIdx = touchedIndices.last()
            val lastTouchedBook = groupItems[lastTouchedIdx]

            // We only suggest a next book if the most advanced book is actually finished
            if (lastTouchedBook.progress?.isFinished != true) continue

            // The candidate is the first book after the last touched book that hasn't been touched yet
            var candidateIdx: Int? = null
            for (i in (lastTouchedIdx + 1) until groupItems.size) {
                val p = groupItems[i].progress
                if (p == null || (p.positionMs == 0L && !p.isFinished)) {
                    candidateIdx = i
                    break
                }
            }

            if (candidateIdx != null) {
                val candidate = groupItems[candidateIdx]
                // Find the maximum lastPlayedAt in this series for sorting
                val maxPlayedAt = groupItems.mapNotNull { it.progress?.lastPlayedAt }.maxOrNull() ?: 0L
                candidates.add(candidate to maxPlayedAt)
            }
        }
    }

    return candidates
        .sortedWith(compareByDescending<Pair<BrowseItem, Long>> { it.second }.thenBy { it.first.book.id })
        .map { it.first }
        .filter { excludeBookId == null || it.book.id != excludeBookId }
}
