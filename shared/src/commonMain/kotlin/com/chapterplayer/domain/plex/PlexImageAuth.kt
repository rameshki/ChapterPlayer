package com.chapterplayer.domain.plex

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource

/** The sync's words for a Plex server without any music library (Plex keeps audiobooks in music libraries). */
const val PLEX_NO_MUSIC_LIBRARY_TEXT = "This Plex server has no music library; audiobooks must be in a music library"

/** The header Plex reads the token from; the app never puts the token in an address. */
const val PLEX_TOKEN_HEADER = "X-Plex-Token"

/**
 * The token to send with a request for [url] (a cover), or null when it goes to no signed-in Plex server. The address
 * must sit under a Plex server's base address (scheme, host, port and path, up to a slash), so the token never goes to
 * another host, another port, or a server whose address only starts the same (`:3240` is not `:32400`). The server's
 * other connections ([ServerConfig.alternateBaseUrls], from plex.tv) count as its addresses too, so a cover saved
 * under the address used before a switch still loads.
 */
fun plexTokenFor(url: String, servers: List<ServerConfig>): String? =
    servers.firstOrNull { server ->
        server.source == ServerSource.PLEX && server.token.isNotBlank() &&
            plexAddressesOf(server).any { base -> url.startsWith(base.trimEnd('/') + "/") }
    }?.token

/** The addresses that belong to [server]: the one in use, then (Plex only) its other connections. Blank ones are left out. */
fun plexAddressesOf(server: ServerConfig): List<String> =
    (listOf(server.baseUrl) + if (server.source == ServerSource.PLEX) server.alternateBaseUrls else emptyList())
        .filter { it.isNotBlank() }
        .distinct()
