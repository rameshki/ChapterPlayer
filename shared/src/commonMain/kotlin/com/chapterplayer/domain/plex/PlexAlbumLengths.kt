package com.chapterplayer.domain.plex

import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto

/** A track's length: its own, else its first media's; null when neither is known. */
private fun trackLength(track: PlexMetadataDto): Long? =
    (track.durationMs ?: track.media?.firstOrNull()?.durationMs)?.takeIf { it > 0L }

/**
 * The length of each album (by rating key) as the sum of its tracks, from a section's track list (`type=10`). An album
 * with any track of unknown length gets no length, so a partial sum is never taken for the whole book; a track without
 * an album is ignored.
 */
fun albumLengths(tracks: List<PlexMetadataDto>): Map<String, Long> {
    val sums = LinkedHashMap<String, Long>()
    val unknown = HashSet<String>()
    for (track in tracks) {
        val album = track.parentRatingKey?.takeIf { it.isNotBlank() } ?: continue
        val length = trackLength(track)
        if (length == null) unknown += album else sums[album] = (sums[album] ?: 0L) + length
    }
    for (album in unknown) sums.remove(album)
    return sums
}
