package com.chapterplayer.domain.plex

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto

/** The app's book ids for Plex albums start with this; the server only knows the part after it (the album's rating key). */
const val PLEX_ID_PREFIX = "plex_"

/** The album's rating key for an app book id. */
fun plexRatingKey(bookId: String): String = bookId.removePrefix(PLEX_ID_PREFIX)

/** What a music section lists, as far as books are concerned. */
enum class PlexEntryKind { ALBUM, ARTIST, OTHER }

/**
 * An album is a book; an artist is a folder of books (a server that ignored the album filter sends artists, whose
 * children are the albums); anything else (a track, a playlist) is not a book. An entry without a type is read as an album.
 */
fun plexEntryKind(entry: PlexMetadataDto): PlexEntryKind = when (entry.type) {
    null, "album" -> PlexEntryKind.ALBUM
    "artist" -> PlexEntryKind.ARTIST
    else -> PlexEntryKind.OTHER
}

/** [path] (a Plex path such as `/library/metadata/1/thumb/2`) on the server at [baseUrl]. Never carries the token. */
fun plexUrl(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

/**
 * One Plex album as a book. The author is the album's artist; Plex has no narrator field, so it is blank. The length is
 * the album's own when the server sends one (a section listing usually does not; details fill it in from the tracks).
 * The cover and the stream address are plain server addresses: the token goes in a header when they are fetched.
 */
fun plexAlbumToBook(album: PlexMetadataDto, baseUrl: String, serverId: String, sectionId: String): AudioBook =
    AudioBook(
        id = PLEX_ID_PREFIX + album.ratingKey,
        title = album.title.ifBlank { "Unknown Title" },
        author = album.author?.takeIf { it.isNotBlank() } ?: "Unknown Author",
        narrator = "",
        durationMs = album.durationMs?.coerceAtLeast(0L) ?: 0L,
        chapters = emptyList(),
        streamUrl = plexUrl(baseUrl, "/library/metadata/${album.ratingKey}/children"),
        coverUrl = album.thumb?.takeIf { it.isNotBlank() }?.let { plexUrl(baseUrl, it) },
        source = ServerSource.PLEX,
        serverId = serverId,
        libraryId = sectionId,
        addedAt = (album.addedAt ?: 0L) * 1000L, // Plex gives seconds
    )
