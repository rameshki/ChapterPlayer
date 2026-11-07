package com.chapterplayer.domain.browse

/** The quick filters under the Library views. Each stands for one combination of the filters. */
enum class FilterChip { ALL, FAVOURITES, DOWNLOADED, IN_PROGRESS, NOT_STARTED, FINISHED }

/** The chips offered: without downloads on this platform (iOS for now) there is no Downloaded chip. */
fun offeredChips(downloads: Boolean): List<FilterChip> =
    FilterChip.entries.filter { downloads || it != FilterChip.DOWNLOADED }

/** The chip that stands for [query], or null when the Filter sheet made a combination no chip represents. The sort never matters. */
fun chipFor(query: BrowseQuery): FilterChip? = when {
    query.favouritesOnly -> FilterChip.FAVOURITES.takeIf { query.progress == ProgressFilter.ALL && query.download == DownloadFilter.ALL }
    query.progress == ProgressFilter.ALL && query.download == DownloadFilter.ALL -> FilterChip.ALL
    query.progress == ProgressFilter.ALL && query.download == DownloadFilter.DOWNLOADED -> FilterChip.DOWNLOADED
    query.progress == ProgressFilter.IN_PROGRESS && query.download == DownloadFilter.ALL -> FilterChip.IN_PROGRESS
    query.progress == ProgressFilter.NOT_STARTED && query.download == DownloadFilter.ALL -> FilterChip.NOT_STARTED
    query.progress == ProgressFilter.FINISHED && query.download == DownloadFilter.ALL -> FilterChip.FINISHED
    else -> null
}

/** The query a chip stands for, keeping the sort and the server choice of [current]; only the Favourites chip keeps favourites only. */
fun queryFor(chip: FilterChip, current: BrowseQuery = BrowseQuery()): BrowseQuery = chipQuery(chip, current.copy(favouritesOnly = false))

private fun chipQuery(chip: FilterChip, current: BrowseQuery): BrowseQuery = when (chip) {
    FilterChip.ALL -> current.copy(progress = ProgressFilter.ALL, download = DownloadFilter.ALL)
    FilterChip.FAVOURITES -> current.copy(progress = ProgressFilter.ALL, download = DownloadFilter.ALL, favouritesOnly = true)
    FilterChip.DOWNLOADED -> current.copy(progress = ProgressFilter.ALL, download = DownloadFilter.DOWNLOADED)
    FilterChip.IN_PROGRESS -> current.copy(progress = ProgressFilter.IN_PROGRESS, download = DownloadFilter.ALL)
    FilterChip.NOT_STARTED -> current.copy(progress = ProgressFilter.NOT_STARTED, download = DownloadFilter.ALL)
    FilterChip.FINISHED -> current.copy(progress = ProgressFilter.FINISHED, download = DownloadFilter.ALL)
}

/** The note Home and Search show while the Library's filters hide books there too, and its clear button's label. */
data class FilterHint(val text: String, val clearLabel: String)

/**
 * The hint for [query], or null when it hides nothing. A server filter alone keeps its own words; any other filter
 * (progress, download, favourites only, also with a server filter) is "Filters on" with "Clear filters".
 */
fun filterHintFor(query: BrowseQuery): FilterHint? = when {
    !isFiltering(query) -> null
    query == BrowseQuery(sort = query.sort, serverIds = query.serverIds) -> FilterHint("Server filter on", "Show all servers")
    else -> FilterHint("Filters on", "Clear filters")
}

/** [query] with every filter off; the sort stays (it hides nothing). */
fun withoutFilters(query: BrowseQuery): BrowseQuery = BrowseQuery(sort = query.sort)

/** Whether [query] hides any book (the sort does not). */
fun isFiltering(query: BrowseQuery): Boolean =
    query.progress != ProgressFilter.ALL || query.download != DownloadFilter.ALL || query.serverIds.isNotEmpty() || query.favouritesOnly
