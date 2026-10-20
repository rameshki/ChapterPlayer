package com.chapterplayer.data.model

/** A reference to a series a book belongs to, with the book's number in it when the server has one. */
data class SeriesRef(val name: String, val sequence: String?)

/** What the details screen loads for one book beyond the library list: chapters, description, genres, year and series. */
data class BookDetails(
    val chapters: List<Chapter>,
    val description: String?,
    val genres: List<String>,
    val publishedYear: String?,
    val series: List<SeriesRef>
)
