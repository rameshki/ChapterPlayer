package com.chapterplayer.data.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.SessionError
import com.chapterplayer.domain.car.CarFailure
import com.chapterplayer.domain.car.CarItem
import com.chapterplayer.domain.car.parseCarNodeId
import com.chapterplayer.domain.covers.bookArtworkUri

/** What a car row becomes in Media3, before any Android object is built (so it can be tested on the JVM). */
data class CarMediaSpec(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val browsable: Boolean,
    val playable: Boolean,
    val mediaType: Int,
    /** The app's own cover address (`content://<app>.covers/cover/<bookId>`), never the server's; null for folders. */
    val artworkUri: String? = null,
)

/**
 * The Media3 description of [item]. A book's cover is the app's cover provider address under [coverAuthority] (no
 * token, no server address; the provider checks that the reader is the car). Dimmed rows are simply not playable
 * (Media3 has no "unavailable" flag for Android Auto).
 */
fun carMediaSpec(item: CarItem, coverAuthority: String? = null): CarMediaSpec {
    val type = when {
        item.browsable -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        parseCarNodeId(item.id)?.kind == "chapter" -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
        parseCarNodeId(item.id) == null -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
        else -> MediaMetadata.MEDIA_TYPE_MIXED
    }
    return CarMediaSpec(
        mediaId = item.id,
        title = item.title,
        subtitle = item.subtitle,
        browsable = item.browsable,
        playable = item.playable && !item.dimmed,
        mediaType = type,
        artworkUri = if (item.browsable || coverAuthority == null) null else bookArtworkUri(coverAuthority, item.id, item.coverUrl),
    )
}

fun CarMediaSpec.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle.ifEmpty { null })
                .setArtist(subtitle.ifEmpty { null })
                .setIsBrowsable(browsable)
                .setIsPlayable(playable)
                .setMediaType(mediaType)
                .setArtworkUri(artworkUri?.let(Uri::parse))
                .build()
        )
        .build()

/** The Media3 error code for a failed car request. */
fun carErrorCode(reason: CarFailure): Int = when (reason) {
    CarFailure.REFUSED -> SessionError.ERROR_PERMISSION_DENIED
    CarFailure.UNKNOWN_BOOK, CarFailure.NO_MATCH, CarFailure.NO_CHAPTER -> SessionError.ERROR_BAD_VALUE
    CarFailure.OFFLINE -> SessionError.ERROR_IO
    CarFailure.NOTHING_TO_RESUME -> SessionError.ERROR_INVALID_STATE
}
