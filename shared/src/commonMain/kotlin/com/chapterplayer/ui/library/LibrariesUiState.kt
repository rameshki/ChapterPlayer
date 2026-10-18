package com.chapterplayer.ui.library

import com.chapterplayer.data.model.ServerLibraries

/** One library row in the check-list. */
data class LibraryRow(val id: String, val name: String, val checked: Boolean)

/** One server heading with its libraries; an empty list means they appear after the first sync. */
data class ServerSection(
    val serverId: String,
    val title: String,
    val sourceLabel: String,
    val libraries: List<LibraryRow>
) {
    val awaitingFirstSync: Boolean get() = libraries.isEmpty()
}

data class LibrariesUiState(
    val servers: List<ServerSection> = emptyList(),
    /** True when libraries exist but every one of them is turned off. */
    val everythingHidden: Boolean = false,
    val isSyncing: Boolean = false,
    /** A failed toggle or sync, in words for the person. */
    val error: String? = null
)

internal fun ServerLibraries.toSection() = ServerSection(
    serverId = server.id,
    title = server.name,
    sourceLabel = server.source.name,
    libraries = libraries.map { LibraryRow(it.id, it.name, it.enabled) }
)
