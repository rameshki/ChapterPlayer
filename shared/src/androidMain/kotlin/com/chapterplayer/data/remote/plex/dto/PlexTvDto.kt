package com.chapterplayer.data.remote.plex.dto

import com.squareup.moshi.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.squareup.moshi.JsonClass

/**
 * Response from POST /api/v2/pins and GET /api/v2/pins/{id} on plex.tv.
 * Used to drive the OAuth PIN flow — we create a pin, open the browser,
 * then poll until authToken is populated.
 */
@JsonClass(generateAdapter = true)
@Serializable
data class PlexPinDto(
    @Json(name = "id") @SerialName("id") val id: Long,
    @Json(name = "code") @SerialName("code") val code: String,
    @Json(name = "authToken") @SerialName("authToken") val authToken: String? = null   // null until user completes sign-in
)

/**
 * One entry from GET /api/v2/resources — represents a Plex Media Server
 * (or other resource like Plex Cloud) owned by the signed-in account.
 *
 * We filter by provides == "server" to get only media servers.
 */
@JsonClass(generateAdapter = true)
@Serializable
data class PlexResourceDto(
    @Json(name = "name") @SerialName("name") val name: String,
    @Json(name = "provides") @SerialName("provides") val provides: String,          // e.g. "server", "player"
    @Json(name = "accessToken") @SerialName("accessToken") val accessToken: String? = null,   // Token for this specific server (required for shared servers)
    @Json(name = "connections") @SerialName("connections") val connections: List<PlexConnectionDto>
)

/**
 * A single connection URI for a Plex server.
 * A server may have multiple connections (local IP, remote/relay, etc.).
 * We prefer local connections when available.
 */
@JsonClass(generateAdapter = true)
@Serializable
data class PlexConnectionDto(
    @Json(name = "uri") @SerialName("uri") val uri: String,
    @Json(name = "local") @SerialName("local") val local: Boolean
)
