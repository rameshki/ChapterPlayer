package com.chapterplayer.data.model

/**
 * Domain model representing an audiobook from either ABS or Plex.
 * This is the canonical in-app representation — API DTOs are mapped to this.
 *
 * @param id         Unique identifier. Prefixed with source to avoid collisions
 *                   (e.g. "abs_abc123", "plex_456").
 * @param title      Book title.
 * @param author     Author name(s).
 * @param narrator   Narrator name(s), if available.
 * @param durationMs Total audiobook duration in milliseconds.
 * @param chapters   Ordered list of chapters; may be empty if server doesn't expose them.
 * @param streamUrl  Direct streaming URL. For ABS this is the /api/items/{id}/stream endpoint;
 *                   for Plex it's the /library/parts/{partId}/file path.
 * @param coverUrl   URL for the cover image, or null if unavailable.
 * @param source     Which server this book came from.
 * @param serverId   ID of the server config this book belongs to.
 */
data class AudioBook(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val durationMs: Long,
    val chapters: List<Chapter>,
    val streamUrl: String,
    val coverUrl: String?,
    val source: ServerSource,
    val serverId: String,
    /** The server library this book lives in; empty until the library is known. */
    val libraryId: String = "",
    /** When the server added the book, in epoch milliseconds; 0 when unknown. */
    val addedAt: Long = 0L,
    val seriesName: String? = null,
    /** Position in the series as the server wrote it ("3", "3.5"); null when unknown. */
    val seriesSequence: String? = null
)
