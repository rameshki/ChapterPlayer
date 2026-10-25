package com.chapterplayer.domain.plex

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto

/**
 * One audio file of a Plex book, in play order: what playback streams (step 2 of Stage 4). [partKey] is the file's
 * server path (`/library/parts/...`, no token), null when the track has no file; [durationMs] is 0 when unknown.
 */
data class PlexTrack(
    val ratingKey: String,
    val title: String,
    val partKey: String?,
    val durationMs: Long,
    val container: String?,
    val sizeBytes: Long?,
)

/** A Plex book's audio and chapters, across all its tracks. */
data class PlexBookContent(val tracks: List<PlexTrack>, val chapters: List<Chapter>) {
    /** The whole book's length; 0 when no track's length is known. */
    val durationMs: Long get() = tracks.sumOf { it.durationMs }

    /** What a download would fetch, in bytes; null when any track's size is unknown. */
    val sizeBytes: Long?
        get() = if (tracks.isNotEmpty() && tracks.all { it.sizeBytes != null }) tracks.sumOf { it.sizeBytes!! } else null
}

/** Only tracks are audio (an entry without a type, as some servers send, is taken as one). */
fun isPlexTrack(entry: PlexMetadataDto): Boolean = entry.type == null || entry.type == "track"

/**
 * Builds a book from its album's [children] (the track list, in play order) and the [details] fetched for some of the
 * tracks (by rating key, with their chapters: a track list never carries chapters). A detail's fields win over the
 * track list's. Each track's chapters are moved by the length of the tracks before it; a track without chapters is one
 * chapter named after the track. A track with no length takes the end of its last chapter, or else counts as 0.
 */
fun plexBookContent(children: List<PlexMetadataDto>, details: Map<String, PlexMetadataDto>): PlexBookContent {
    val tracks = mutableListOf<PlexTrack>()
    val chapters = mutableListOf<Chapter>()
    var offset = 0L
    children.filter(::isPlexTrack).forEachIndexed { index, listed ->
        val track = details[listed.ratingKey] ?: listed
        val own = track.chapters.orEmpty().sortedBy { it.startTimeMs }
        val media = track.media?.firstOrNull() ?: listed.media?.firstOrNull()
        val part = media?.parts?.firstOrNull()
        val stated = listOfNotNull(track.durationMs, media?.durationMs, part?.durationMs, listed.durationMs).firstOrNull { it > 0L }
        val durationMs = stated ?: own.maxOfOrNull { maxOf(it.endTimeMs ?: 0L, it.startTimeMs) }?.coerceAtLeast(0L) ?: 0L
        val title = track.title.ifBlank { listed.title }.ifBlank { "Track ${index + 1}" }
        tracks += PlexTrack(
            ratingKey = listed.ratingKey,
            title = title,
            partKey = part?.key?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            container = part?.container ?: media?.container,
            sizeBytes = part?.sizeBytes,
        )
        if (own.isEmpty()) {
            chapters += Chapter(id = chapters.size, title = title, startMs = offset, endMs = offset + durationMs)
        } else {
            own.forEachIndexed { i, chapter ->
                val start = chapter.startTimeMs.coerceAtLeast(0L)
                val end = chapter.endTimeMs ?: own.getOrNull(i + 1)?.startTimeMs ?: durationMs
                chapters += Chapter(
                    id = chapters.size,
                    title = chapter.title.ifBlank { "Chapter ${chapters.size + 1}" },
                    startMs = offset + start,
                    endMs = offset + end.coerceAtLeast(start),
                )
            }
        }
        offset += durationMs
    }
    return PlexBookContent(tracks, chapters)
}
