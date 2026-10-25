package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.remote.plex.dto.PlexResourceDto
import com.chapterplayer.data.remote.plex.dto.connectionCandidates
import com.chapterplayer.data.sync.PlexCandidateLookup
import com.chapterplayer.domain.plex.PlexCandidate

/** The product name and version the app sends to plex.tv. */
const val PLEX_PRODUCT = "ChapterPlayer"
const val PLEX_VERSION = "0.1.0"

/**
 * Finds a saved server among the account's resources on plex.tv (asked with the server's own token) by its address:
 * the resource one of whose ranked connections is the server's current address. Null when none matches.
 */
class PlexTvCandidateLookup(
    private val plexTv: PlexTvApiService,
    private val clientIdentifier: PlexClientIdentifier,
) : PlexCandidateLookup {
    override suspend fun candidatesFor(server: ServerConfig): List<PlexCandidate>? {
        val resources = plexTv.getResources(
            token = server.token,
            product = PLEX_PRODUCT,
            version = PLEX_VERSION,
            clientId = clientIdentifier.value,
        )
        return matchingCandidates(server.baseUrl, resources)
    }
}

/** The ranked connections of the server in [resources] that lists [baseUrl] among them, or null. */
fun matchingCandidates(baseUrl: String, resources: List<PlexResourceDto>): List<PlexCandidate>? {
    val wanted = baseUrl.trim().trimEnd('/').lowercase()
    return resources
        .filter { it.provides.contains("server") }
        .map { it.connectionCandidates() }
        .firstOrNull { list -> list.any { it.uri.lowercase() == wanted } }
}
