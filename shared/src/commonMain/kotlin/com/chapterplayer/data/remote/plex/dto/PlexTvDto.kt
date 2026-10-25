package com.chapterplayer.data.remote.plex.dto

import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionInfo
import com.chapterplayer.domain.plex.rankPlexConnections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from POST /api/v2/pins and GET /api/v2/pins/{id} on plex.tv.
 * Used to drive the OAuth PIN flow — we create a pin, open the browser,
 * then poll until authToken is populated.
 */
@Serializable
data class PlexPinDto(
    @SerialName("id") val id: Long,
    @SerialName("code") val code: String,
    @SerialName("authToken") val authToken: String? = null   // null until user completes sign-in
)

/**
 * One entry from GET /api/v2/resources — represents a Plex Media Server
 * (or other resource like Plex Cloud) owned by the signed-in account.
 *
 * We filter by provides == "server" to get only media servers.
 */
@Serializable
data class PlexResourceDto(
    @SerialName("name") val name: String,
    @SerialName("provides") val provides: String,          // e.g. "server", "player"
    @SerialName("accessToken") val accessToken: String? = null,   // Token for this specific server (required for shared servers)
    @SerialName("connections") val connections: List<PlexConnectionDto>,
    @SerialName("clientIdentifier") val clientIdentifier: String? = null,
    /** plex.tv saw a network that refuses `*.plex.direct` names pointing at private addresses. */
    @SerialName("dnsRebindingProtection") val dnsRebindingProtection: Boolean = false,
    @SerialName("natLoopbackSupported") val natLoopbackSupported: Boolean = false,
    @SerialName("httpsRequired") val httpsRequired: Boolean = false
) {
    /** Left out so the access token can never reach a log. */
    override fun toString() = "PlexResourceDto(name=$name, provides=$provides, connections=${connections.size})"
}

/**
 * A single connection URI for a Plex server.
 * A server may have multiple connections (local IP, remote/relay, etc.).
 * We prefer local connections when available.
 */
@Serializable
data class PlexConnectionDto(
    @SerialName("uri") val uri: String,
    @SerialName("local") val local: Boolean,
    @SerialName("address") val address: String = "",
    @SerialName("port") val port: Int = 0,
    @SerialName("protocol") val protocol: String = "",
    @SerialName("relay") val relay: Boolean = false,
    @SerialName("IPv6") val ipv6: Boolean = false
)

/** The server's connections in the order to try them (see [rankPlexConnections]). */
fun PlexResourceDto.connectionCandidates(): List<PlexCandidate> =
    rankPlexConnections(
        connections.map { PlexConnectionInfo(it.uri, it.address, it.port, it.protocol, it.local, it.relay, it.ipv6) },
        dnsRebindingProtection = dnsRebindingProtection,
        httpsRequired = httpsRequired,
    )
