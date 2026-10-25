package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource

/** One server row of the Filter sheet: [count] is its number of books before any filter. */
data class ServerFilterOption(
    val id: String,
    val name: String,
    val typeLabel: String,
    val count: Int,
    val selected: Boolean
)

/** The short type label shown beside a server's name. */
fun ServerSource.shortLabel(): String = when (this) {
    ServerSource.ABS -> "ABS"
    ServerSource.PLEX -> "Plex"
}

/** The selected ids that still belong to one of [allIds]; empty means every server. */
private fun liveSelection(selected: Set<String>, allIds: Collection<String>): Set<String> =
    selected.filterTo(mutableSetOf()) { it in allIds }

/**
 * The server rows of the Filter sheet, in the order of [servers], counting [allItems] (the unfiltered
 * books). Empty with fewer than two servers, when there is nothing to choose.
 */
fun serverFilterOptions(
    servers: List<ServerConfig>,
    allItems: List<BrowseItem>,
    selected: Set<String>
): List<ServerFilterOption> {
    if (servers.size < 2) return emptyList()
    // A merged book counts once on each of its servers.
    val counts = allItems.flatMap { it.serverIds }.groupingBy { it }.eachCount()
    val live = liveSelection(selected, servers.map { it.id })
    return servers.map { server ->
        ServerFilterOption(
            id = server.id,
            name = server.name,
            typeLabel = server.source.shortLabel(),
            count = counts[server.id] ?: 0,
            selected = live.isEmpty() || server.id in live
        )
    }
}

/**
 * The server choice after ticking or unticking [id]. With every server shown (an empty set) unticking
 * one keeps the others; ticking the last missing one goes back to every server; the last ticked server
 * stays ticked, so the choice never becomes "no server".
 */
fun toggleServer(selected: Set<String>, id: String, allIds: List<String>): Set<String> {
    val live = liveSelection(selected, allIds)
    val current = if (live.isEmpty()) allIds.toSet() else live
    val next = if (id in current) current - id else current + id
    return when {
        next.isEmpty() -> current.takeIf { it.size < allIds.size } ?: emptySet()
        next.containsAll(allIds) -> emptySet()
        else -> next
    }
}

/** The chip label for an active server choice ("Home server", "2 servers"), or null when no server is filtered out. */
fun serverChipLabel(selected: Set<String>, servers: List<ServerConfig>): String? {
    if (servers.size < 2) return null
    val live = liveSelection(selected, servers.map { it.id })
    return when (live.size) {
        0 -> null
        1 -> servers.first { it.id in live }.name
        else -> "${live.size} servers"
    }
}
