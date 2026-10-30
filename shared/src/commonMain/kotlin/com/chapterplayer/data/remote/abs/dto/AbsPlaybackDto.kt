package com.chapterplayer.data.remote.abs.dto

import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.playback.PlaybackTrack
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** The answer to `POST /api/items/{id}/play`; times are in seconds. */
@Serializable
data class AbsPlaySessionDto(
    @SerialName("id") val id: String,
    @SerialName("duration") val duration: Double,
    @SerialName("startTime") val startTime: Double = 0.0,
    @SerialName("currentTime") val currentTime: Double = 0.0,
    @SerialName("audioTracks") val audioTracks: List<AbsPlayTrackDto> = emptyList(),
    @SerialName("chapters") val chapters: List<AbsItemDetailChapterDto> = emptyList(),
)

/** One audio file in a play session; [contentUrl] is relative to the server address. */
@Serializable
data class AbsPlayTrackDto(
    @SerialName("index") val index: Int,
    @SerialName("startOffset") val startOffset: Double,
    @SerialName("duration") val duration: Double,
    @SerialName("mimeType") val mimeType: String = "",
    @SerialName("contentUrl") val contentUrl: String,
)

/** The body of the play request. The fixed fields are always sent. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AbsPlayRequest(
    val deviceInfo: AbsPlayDeviceInfo,
    @EncodeDefault val forceDirectPlay: Boolean = true,
    @EncodeDefault val forceTranscode: Boolean = false,
    @EncodeDefault val supportedMimeTypes: List<String> = SUPPORTED_MIME_TYPES,
    @EncodeDefault val mediaPlayer: String = "exoplayer",
)

/** Names this app to the server. */
@Serializable
data class AbsPlayDeviceInfo(val clientName: String, val deviceId: String)

/** The body of the close request: where listening stopped and how long it ran, both in seconds. */
@Serializable
data class AbsCloseSessionRequest(val currentTime: Double, val timeListened: Double)

internal val SUPPORTED_MIME_TYPES = listOf(
    "audio/mpeg", "audio/mp4", "audio/flac", "audio/ogg", "audio/aac", "audio/x-m4b", "audio/m4b", "audio/x-m4a"
)

private fun Double.toMs(): Long = (this * 1000).roundToLong()

/** The session in the app's terms: milliseconds, tracks in index order, absolute urls built from [baseUrl]. */
fun AbsPlaySessionDto.toPlaybackSession(serverId: String, bookId: String, baseUrl: String): PlaybackSession =
    PlaybackSession(
        sessionId = id,
        serverId = serverId,
        bookId = bookId,
        durationMs = duration.toMs(),
        startPositionMs = currentTime.toMs(),
        tracks = audioTracks.sortedBy { it.index }.map {
            PlaybackTrack(
                index = it.index,
                startMs = it.startOffset.toMs(),
                durationMs = it.duration.toMs(),
                mimeType = it.mimeType,
                url = baseUrl.trimEnd('/') + (if (it.contentUrl.startsWith("/")) "" else "/") + it.contentUrl
            )
        },
        chapters = chapters.map { it.toChapter() }
    )
