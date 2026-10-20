package com.chapterplayer.data.remote.abs.dto

import com.chapterplayer.data.source.ABS_ID_PREFIX
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** One bookmark of `/api/me`: [time] is in seconds, [createdAt] in epoch milliseconds. */
@Serializable
data class AbsBookmarkDto(
    @SerialName("libraryItemId") val libraryItemId: String = "",
    @SerialName("time") val time: Double = 0.0,
    @SerialName("title") val title: String = "",
    @SerialName("createdAt") val createdAt: Long = 0,
) {
    /** The app's terms: the book id carries the app's prefix, the time is whole seconds (rounded), the row is in sync. */
    fun toBookmark(): Bookmark = Bookmark(
        bookId = ABS_ID_PREFIX + libraryItemId,
        timeSec = time.roundToLong(),
        title = title,
        createdAt = createdAt,
        state = BookmarkState.SYNCED
    )
}

/** `GET /api/me`: only the bookmark list is read here (progress has its own reader, [AbsMeDto]). */
@Serializable
data class AbsMeBookmarksDto(
    @SerialName("bookmarks") val bookmarks: List<AbsBookmarkDto> = emptyList(),
)

/** The body of a bookmark create or rename: the whole second and the title. */
@Serializable
data class AbsBookmarkRequest(val time: Long, val title: String)
