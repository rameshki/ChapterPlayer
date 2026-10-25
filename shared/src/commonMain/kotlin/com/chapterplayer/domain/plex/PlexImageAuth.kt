package com.chapterplayer.domain.plex

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource

/** The header Plex reads the token from; the app never puts the token in an address. */
const val PLEX_TOKEN_HEADER = "X-Plex-Token"

/**
 * The token to send with a request for [url] (a cover), or null when it goes to no signed-in Plex server. The address
 * must sit under a Plex server's base address (scheme, host, port and path, up to a slash), so the token never goes to
 * another host, another port, or a server whose address only starts the same (`:3240` is not `:32400`).
 */
fun plexTokenFor(url: String, servers: List<ServerConfig>): String? =
    servers.firstOrNull { server ->
        server.source == ServerSource.PLEX && server.token.isNotBlank() && server.baseUrl.isNotBlank() &&
            url.startsWith(server.baseUrl.trimEnd('/') + "/")
    }?.token
