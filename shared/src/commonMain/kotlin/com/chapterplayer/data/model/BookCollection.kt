package com.chapterplayer.data.model

/** A server-side collection: a named list of books. [bookIds] are [AudioBook.id] values, in order. */
data class BookCollection(
    val serverId: String,
    val id: String,
    val name: String,
    val bookIds: List<String>,
)
