package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource

/*
 * The name each server goes by in the source sheets and the several-servers settings, so two servers never read the
 * same: a blank name is the server type; names that are the same (ignoring case and spaces) get the address host
 * ("Home (books.example)") when the hosts differ, else a number in list order ("Home #2"). Never an id or a token
 * (stored addresses never hold a token; user info before "@" is dropped).
 */

/** The full name of a kind of server, as Settings, the sheets and Diagnostics show it. */
fun serverKindName(source: ServerSource): String = when (source) {
    ServerSource.ABS -> "Audiobookshelf"
    ServerSource.PLEX -> "Plex"
    ServerSource.JELLYFIN -> "Jellyfin"
}

/** The display name of every server in [servers], by id. */
fun serverDisplayNames(servers: List<ServerConfig>): Map<String, String> {
    fun base(server: ServerConfig) = server.name.trim().ifEmpty { serverKindName(server.source) }
    val result = LinkedHashMap<String, String>()
    for (group in servers.groupBy { base(it).lowercase() }.values) {
        if (group.size == 1) {
            result[group[0].id] = base(group[0])
            continue
        }
        val hosts = group.map { addressHost(it.baseUrl) }
        val byHost = hosts.all { it.isNotEmpty() } && hosts.toSet().size == hosts.size
        group.forEachIndexed { i, server ->
            result[server.id] = if (byHost) "${base(server)} (${hosts[i]})" else "${base(server)} #${i + 1}"
        }
    }
    return servers.associate { it.id to result.getValue(it.id) }
}

/** "books.example:13378" for "https://user@books.example:13378/abs"; empty when [address] has no scheme. */
private fun addressHost(address: String): String {
    if (!address.contains("://")) return ""
    return address.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').lowercase()
}
