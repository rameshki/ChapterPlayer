package com.chapterplayer.data.remote.jellyfin.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The parts of Jellyfin's answers the app reads (v12.4 `BaseItemDto` and friends). Jellyfin writes PascalCase names;
 * every field is optional or defaulted, because servers leave out what they do not have (and older versions differ).
 * Unknown keys are ignored by `ApiJson`.
 */

/** `GET /System/Info/Public` (no auth): is this Jellyfin, which version, which server. */
@Serializable
data class JellyfinPublicInfoDto(
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
)

/** The body of `POST /Users/AuthenticateByName`. */
@Serializable
internal data class JellyfinAuthenticateByNameDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
) {
    /** Left out so the password can never reach a log. */
    override fun toString() = "JellyfinAuthenticateByNameDto(username=$username)"
}

@Serializable
data class JellyfinUserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
)

/** The sign-in answer. [accessToken] is the credential: [toString] leaves it out. */
@Serializable
data class JellyfinAuthResultDto(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("User") val user: JellyfinUserDto? = null,
) {
    override fun toString() = "JellyfinAuthResultDto(serverId=$serverId, user=$user)"
}

/** A page of items (`/Items`, `/UserViews`). */
@Serializable
data class JellyfinItemsPageDto(
    @SerialName("Items") val items: List<JellyfinItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
    @SerialName("StartIndex") val startIndex: Int? = null,
)

@Serializable
data class JellyfinPersonDto(
    @SerialName("Name") val name: String? = null,
    /** `Author`, `Narrator` (12.0+ for audiobooks), ... */
    @SerialName("Type") val type: String? = null,
)

@Serializable
data class JellyfinNameDto(
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class JellyfinChapterDto(
    @SerialName("StartPositionTicks") val startPositionTicks: Long? = null,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class JellyfinMediaSourceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
)

/** One user's play state of an item. Ticks are 100 ns (10,000,000 per second). */
@Serializable
data class JellyfinUserDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("Played") val played: Boolean? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
    /** ISO-8601, such as `2026-10-20T08:30:00.0000000Z`. */
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
)

/** One item: a library view (`CollectionFolder`), an `AudioBook`, a folder, ... */
@Serializable
data class JellyfinItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("People") val people: List<JellyfinPersonDto> = emptyList(),
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("Studios") val studios: List<JellyfinNameDto> = emptyList(),
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("Chapters") val chapters: List<JellyfinChapterDto> = emptyList(),
    @SerialName("MediaSources") val mediaSources: List<JellyfinMediaSourceDto> = emptyList(),
    @SerialName("UserData") val userData: JellyfinUserDataDto? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
)
