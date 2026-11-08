package com.chapterplayer.domain.jellyfin

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinChapterDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinUserDataDto
import com.chapterplayer.domain.playback.ServerProgress
import kotlin.time.Instant

/** Jellyfin counts time in ticks of 100 ns: 10,000 per millisecond. */
const val JELLYFIN_TICKS_PER_MS = 10_000L

/** [ticks] (null or negative read as 0) in milliseconds. */
fun jellyfinTicksToMs(ticks: Long?): Long = ((ticks ?: 0L) / JELLYFIN_TICKS_PER_MS).coerceAtLeast(0L)

/** An ISO-8601 time Jellyfin sends (7 fractional digits, such as `2026-10-01T12:00:00.0000000Z`) in epoch ms; 0 when unreadable. */
fun jellyfinTimeMs(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return try {
        Instant.parse(iso).toEpochMilliseconds()
    } catch (e: IllegalArgumentException) {
        0L
    }
}

/**
 * One `AudioBook` item as a book; null when it has no id or is not an `AudioBook`. The author is the first `Author`
 * person (12.0+), else the album artist, else the first artist, else blank; the narrators are the `Narrator` people.
 * The cover is the item's primary image under [base] (anonymous: no token), or none. The stream address is built when
 * playback starts.
 */
fun jellyfinItemToBook(item: JellyfinItemDto, base: String, serverId: String, libraryId: String): AudioBook? {
    val id = item.id?.takeIf { it.isNotBlank() } ?: return null
    if (!item.type.equals("AudioBook", ignoreCase = true)) return null
    val author = item.people.firstOrNull { it.type.equals("Author", ignoreCase = true) && !it.name.isNullOrBlank() }?.name
        ?: item.albumArtist?.takeIf { it.isNotBlank() }
        ?: item.artists.firstOrNull { it.isNotBlank() }
        ?: ""
    val narrator = item.people
        .filter { it.type.equals("Narrator", ignoreCase = true) && !it.name.isNullOrBlank() }
        .joinToString(", ") { it.name!! }
    return AudioBook(
        id = jellyfinBookId(id),
        title = item.name.orEmpty(),
        author = author,
        narrator = narrator,
        durationMs = jellyfinTicksToMs(item.runTimeTicks),
        chapters = emptyList(),
        streamUrl = "",
        coverUrl = item.imageTags["Primary"]?.takeIf { it.isNotBlank() }?.let { jellyfinCoverUrl(base, id, it) },
        source = ServerSource.JELLYFIN,
        serverId = serverId,
        libraryId = libraryId,
        addedAt = jellyfinTimeMs(item.dateCreated),
        seriesName = item.seriesName?.takeIf { it.isNotBlank() },
    )
}

/** `{base}/Items/{id}/Images/Primary?tag={tag}&maxWidth=600`: the path of [base] kept once, never a token. */
fun jellyfinCoverUrl(base: String, itemId: String, tag: String): String =
    base.trimEnd('/') + "/Items/" + jellyfinUrlPart(itemId) + "/Images/Primary?tag=" + jellyfinUrlPart(tag) + "&maxWidth=600"

/**
 * The book's chapters: sorted by start, without starts that are missing, negative, at or past the end or repeated;
 * each ends where the next starts and the last at [durationMs]; the first starts at 0. No usable chapter (or no
 * known length) is one chapter, "Part 1", over the whole book.
 */
fun jellyfinChapters(chapters: List<JellyfinChapterDto>, durationMs: Long): List<Chapter> {
    val whole = listOf(Chapter(0, "Part 1", 0L, durationMs.coerceAtLeast(0L)))
    if (durationMs <= 0L) return whole
    val starts = chapters
        .mapNotNull { c -> c.startPositionTicks?.takeIf { it >= 0 }?.let { jellyfinTicksToMs(it) to c.name } }
        .filter { (start, _) -> start < durationMs }
        .sortedBy { it.first }
        .distinctBy { it.first }
    if (starts.isEmpty()) return whole
    return starts.mapIndexed { i, (start, name) ->
        Chapter(
            id = i,
            title = name?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}",
            startMs = if (i == 0) 0L else start,
            endMs = if (i + 1 < starts.size) starts[i + 1].first else durationMs,
        )
    }
}

/** The server's default end band for audiobooks (`MaxAudiobookResume`, 5 min): less than this left counts as the end. */
const val JELLYFIN_END_BAND_MS = 5 * 60_000L

/**
 * The server's progress from the user's play state. Played is finished at the end only when its position is 0 or in
 * the end band ([JELLYFIN_END_BAND_MS] or less left): Jellyfin keeps Played while a finished book is listened to again,
 * so a played book with a position earlier in it is in progress there. A position of 0 that is not played is no
 * progress at all (null), so the server's 5-minute rule never pulls a book back to the start. The time is
 * `LastPlayedDate` (0 when missing, so it is never newer than anything).
 */
fun jellyfinServerProgress(data: JellyfinUserDataDto?, durationMs: Long): ServerProgress? {
    if (data == null) return null
    val updatedAt = jellyfinTimeMs(data.lastPlayedDate)
    val position = jellyfinTicksToMs(data.playbackPositionTicks)
    if (data.played == true && (position <= 0L || durationMs - position < JELLYFIN_END_BAND_MS)) {
        return ServerProgress(durationMs, durationMs, true, updatedAt)
    }
    if (position <= 0L) return null
    return ServerProgress(if (durationMs > 0L) position.coerceAtMost(durationMs) else position, durationMs, false, updatedAt)
}

private const val HEX = "0123456789ABCDEF"

/** [value] percent-encoded except the unreserved characters (ids and tags are hex, so this is only a safeguard). */
internal fun jellyfinUrlPart(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') append(c)
        else append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
    }
}
