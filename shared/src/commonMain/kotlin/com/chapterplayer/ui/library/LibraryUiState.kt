package com.chapterplayer.ui.library

import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.domain.browse.BookGroup
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BrowseQuery
import com.chapterplayer.domain.browse.DownloadFilter
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.ProgressFilter
import com.chapterplayer.domain.browse.ServerFilterOption
import com.chapterplayer.domain.browse.chipFor
import com.chapterplayer.domain.browse.isDimmed
import com.chapterplayer.domain.browse.isFiltering

/**
 * Complete UI state for the Library screen.
 *
 * @param items the books to show in the [LibraryView.LIBRARY] view (filtered and sorted); empty in the other views.
 * @param groups the groups to list in the other views, each with only the books that passed the filters.
 * @param hasBooks whether any book is cached, before the filters; tells "nothing yet" from "filtered out".
 */
data class LibraryUiState(
    val view: LibraryView = LibraryView.LIBRARY,
    val layout: LibraryLayout = LibraryLayout.LARGE_GRID,
    val query: BrowseQuery = BrowseQuery(),
    val items: List<BrowseItem> = emptyList(),
    val groups: List<BookGroup> = emptyList(),
    val hasBooks: Boolean = false,
    val hasServers: Boolean = false,
    /** Servers have library rows and the user turned every one off; Pull to refresh cannot help then. */
    val allLibrariesHidden: Boolean = false,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    /** Display names of servers whose sign-in is gone (their stored token is blank). */
    val signInRequiredNames: List<String> = emptyList(),
    val allServersNeedSignIn: Boolean = false,
    val online: Boolean = true,
    val downloadedOnly: Boolean = false,
    /** The Server section of the Filter sheet; empty (and hidden) with fewer than two servers. */
    val serverOptions: List<ServerFilterOption> = emptyList(),
    /** The label of the active server filter chip, or null when every server shows. */
    val serverChip: String? = null
) {
    /** The highlighted chip, or null when the Filter sheet made a combination no chip stands for. */
    val chip: FilterChip? get() = chipFor(query)

    val isContentEmpty: Boolean get() = if (view.isGrouped) groups.isEmpty() else items.isEmpty()

    /** Books are cached but the filters hide every one (in this view). */
    val emptyBecauseOfFilter: Boolean get() = hasBooks && isContentEmpty && isFiltering(query)

    /** The Downloaded chip is on and no book is on the phone. */
    val nothingDownloaded: Boolean
        get() = hasBooks && isContentEmpty &&
            query.download == DownloadFilter.DOWNLOADED && query.progress == ProgressFilter.ALL && query.serverIds.isEmpty()

    /** Whether this book cannot play now (offline or Downloaded only, and not on the phone). */
    fun dimmed(item: BrowseItem): Boolean = isDimmed(item.downloadState, online, downloadedOnly)
}

/**
 * Whether there are known libraries and every one is turned off. A server that has not synced its
 * libraries yet has no rows and does not count.
 */
fun allLibrariesHidden(libraries: List<LibraryInfo>, servers: List<ServerConfig>): Boolean {
    val excluded = servers.associate { it.id to it.excludedLibraryIds }
    val known = libraries.filter { it.serverId in excluded }
    return known.isNotEmpty() && known.all { it.id in excluded.getValue(it.serverId) }
}
