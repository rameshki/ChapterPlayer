package com.chapterplayer.data.model

/** A reference to a series a book belongs to, with the book's number in it when the server has one. */
data class SeriesRef(val name: String, val sequence: String?)

/** What the details screen loads for one book beyond the library list: chapters, description, genres, year, series and download size. */
data class BookDetails(
    val chapters: List<Chapter>,
    val description: String?,
    val genres: List<String>,
    val publishedYear: String?,
    val series: List<SeriesRef>,
    /** What a download of the book would fetch, in bytes; null when the server did not say. */
    val downloadSizeBytes: Long? = null,
    /** The book's length when details know it better than the library list (a Plex album's tracks); null otherwise. */
    val durationMs: Long? = null
)
