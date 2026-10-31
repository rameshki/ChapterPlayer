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

/**
 * Names this app to the server. [manufacturer] and [model] are left out when null (the Android request has never
 * sent them).
 */
@Serializable
data class AbsPlayDeviceInfo(
    val clientName: String,
    val deviceId: String,
    val manufacturer: String? = null,
    val model: String? = null,
)

/**
 * What the platform's player can play, as the play request tells Audiobookshelf. With [forceDirectPlay] off the server
 * streams a book whose files are not in [supportedMimeTypes] as HLS (which every player here plays) instead of handing
 * over files the player cannot read.
 */
class AbsPlayerProfile(
    val forceDirectPlay: Boolean,
    val supportedMimeTypes: List<String>,
    val mediaPlayer: String,
    val manufacturer: String? = null,
    val model: String? = null,
) {
    /** The play request for this player. */
    fun request(clientName: String, deviceId: String) = AbsPlayRequest(
        deviceInfo = AbsPlayDeviceInfo(clientName, deviceId, manufacturer, model),
        forceDirectPlay = forceDirectPlay,
        supportedMimeTypes = supportedMimeTypes,
        mediaPlayer = mediaPlayer,
    )

    companion object {
        /** ExoPlayer plays every file the server has (Ogg included): always the files, as the app always asked. */
        val Android = AbsPlayerProfile(forceDirectPlay = true, supportedMimeTypes = SUPPORTED_MIME_TYPES, mediaPlayer = "exoplayer")

        /**
         * AVPlayer: no Ogg or Opus (those books come as HLS), and the server may choose. Only formats AVFoundation is
         * documented to play.
         */
        val Ios = AbsPlayerProfile(
            forceDirectPlay = false,
            supportedMimeTypes = IOS_SUPPORTED_MIME_TYPES,
            mediaPlayer = "AVPlayer",
            manufacturer = "Apple",
            model = "iPhone",
        )
    }
}

/** What AVFoundation plays as files (MP3, AAC/ALAC in MP4/M4A/M4B, FLAC, WAV, AIFF). Never Ogg, Opus or WebM. */
internal val IOS_SUPPORTED_MIME_TYPES = listOf(
    "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/x-m4b", "audio/m4b", "audio/aac",
    "audio/x-aac", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/aiff", "audio/x-aiff",
)

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
