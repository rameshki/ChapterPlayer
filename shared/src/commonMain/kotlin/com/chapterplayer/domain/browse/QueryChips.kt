package com.chapterplayer.domain.browse

/** The quick filters under the Library views. Each stands for one combination of the two filters. */
enum class FilterChip { ALL, DOWNLOADED, IN_PROGRESS, NOT_STARTED, FINISHED }

/** The chip that stands for [query], or null when the Filter sheet made a combination no chip represents. The sort never matters. */
fun chipFor(query: BrowseQuery): FilterChip? = when {
    query.progress == ProgressFilter.ALL && query.download == DownloadFilter.ALL -> FilterChip.ALL
    query.progress == ProgressFilter.ALL && query.download == DownloadFilter.DOWNLOADED -> FilterChip.DOWNLOADED
    query.progress == ProgressFilter.IN_PROGRESS && query.download == DownloadFilter.ALL -> FilterChip.IN_PROGRESS
    query.progress == ProgressFilter.NOT_STARTED && query.download == DownloadFilter.ALL -> FilterChip.NOT_STARTED
    query.progress == ProgressFilter.FINISHED && query.download == DownloadFilter.ALL -> FilterChip.FINISHED
    else -> null
}

/** The query a chip stands for, keeping the sort of [current]. */
fun queryFor(chip: FilterChip, current: BrowseQuery = BrowseQuery()): BrowseQuery = when (chip) {
    FilterChip.ALL -> current.copy(progress = ProgressFilter.ALL, download = DownloadFilter.ALL)
    FilterChip.DOWNLOADED -> current.copy(progress = ProgressFilter.ALL, download = DownloadFilter.DOWNLOADED)
    FilterChip.IN_PROGRESS -> current.copy(progress = ProgressFilter.IN_PROGRESS, download = DownloadFilter.ALL)
    FilterChip.NOT_STARTED -> current.copy(progress = ProgressFilter.NOT_STARTED, download = DownloadFilter.ALL)
    FilterChip.FINISHED -> current.copy(progress = ProgressFilter.FINISHED, download = DownloadFilter.ALL)
}

/** Whether [query] hides any book (the sort does not). */
fun isFiltering(query: BrowseQuery): Boolean =
    query.progress != ProgressFilter.ALL || query.download != DownloadFilter.ALL || query.serverIds.isNotEmpty()
