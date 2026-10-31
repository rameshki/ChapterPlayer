package com.chapterplayer.data.playback

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.plex.plexAddressesOf
import kotlinx.io.IOException

/*
 * Which server an audio address belongs to and what may be said about a failed start. Shared by Android's data source
 * (AuthorizingDataSourceFactory) and the iOS player (StreamAuthorizer).
 */

/** A configured server's address and the access token to use against it. Never logged. */
class ServerBearer(val baseUrl: String, val token: String) {
    override fun toString() = "ServerBearer(baseUrl=$baseUrl)"
}

/**
 * The server a request address belongs to: the one with the longest base address that is a prefix of
 * [url] (on a path boundary). Scheme and host are compared without regard to case, a trailing slash on
 * the base is ignored, and the port must match. Servers without a token are skipped. Null when none matches.
 */
fun serverFor(url: String, servers: List<ServerBearer>): ServerBearer? {
    val target = normalizeAddress(url)
    var best: ServerBearer? = null
    var bestLength = -1
    for (server in servers) {
        if (server.token.isBlank()) continue
        val base = normalizeAddress(server.baseUrl).trimEnd('/')
        if (base.isEmpty() || base.length <= bestLength) continue
        val matches = target == base ||
            (target.startsWith(base) && target[base.length].let { it == '/' || it == '?' || it == '#' })
        if (matches) {
            best = server
            bestLength = base.length
        }
    }
    return best
}

/** The access token for the server [url] belongs to, or null when no server matches. See [serverFor]. */
fun bearerFor(url: String, servers: List<ServerBearer>): String? = serverFor(url, servers)?.token

/**
 * The configured Audiobookshelf or Plex server [url] belongs to (see [serverFor]); a Plex server's verified other
 * connections count as its addresses too ([plexAddressesOf]). Null when none matches or it has no token.
 */
fun streamServerFor(url: String, servers: List<ServerConfig>): ServerConfig? {
    val bearers = servers
        .filter { it.source == ServerSource.ABS || it.source == ServerSource.PLEX }
        .flatMap { s -> plexAddressesOf(s).map { address -> s to ServerBearer(address, s.token) } }
    val match = serverFor(url, bearers.map { it.second }) ?: return null
    return bearers.first { it.second === match }.first
}

// Lower-cases the scheme and the host (with its port); the path stays as written.
private fun normalizeAddress(address: String): String {
    val trimmed = address.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed
    val authorityStart = schemeEnd + 3
    var authorityEnd = authorityStart
    while (authorityEnd < trimmed.length && trimmed[authorityEnd] !in "/?#") authorityEnd++
    return trimmed.substring(0, authorityEnd).lowercase() + trimmed.substring(authorityEnd)
}

/** Whether two addresses have the same scheme, host and port (case-insensitive). */
internal fun sameHost(a: String, b: String): Boolean = originOf(a) == originOf(b)

private fun originOf(address: String): String {
    val trimmed = address.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed.lowercase()
    var end = schemeEnd + 3
    while (end < trimmed.length && trimmed[end] !in "/?#") end++
    return trimmed.substring(0, end).lowercase()
}

private val OWN_START_MESSAGES = setOf(
    "Unknown book",
    "The book's server is no longer configured",
    "This server cannot play books yet",
    com.chapterplayer.domain.plex.PLEX_AUDIO_MISSING_TEXT,
)

/**
 * A fixed, safe text for why a playback session could not start. Raw exception text can hold an address
 * or a piece of the server's answer, so only the repository's own fixed messages pass through.
 */
internal fun safeStartMessage(failure: Throwable): String = when {
    failure is com.chapterplayer.data.auth.SignInRequiredException -> SIGN_IN_AGAIN_TEXT
    failure is com.chapterplayer.data.remote.HttpStatusException -> when (failure.status.value) {
        401, 403 -> SIGN_IN_AGAIN_TEXT
        404 -> "This book is no longer on the server"
        else -> "The server could not start playback"
    }
    failure is IOException -> "Cannot reach the server"
    failure.message in OWN_START_MESSAGES -> failure.message!!
    else -> PlaybackStartException.DEFAULT_MESSAGE
}

internal const val SIGN_IN_AGAIN_TEXT = "Sign in again in Settings"
