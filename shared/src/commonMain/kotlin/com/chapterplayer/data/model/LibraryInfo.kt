package com.chapterplayer.data.model

/** A book library (Audiobookshelf) or music section (Plex) on a server, whether or not the user turned it off. */
data class LibraryInfo(
    val serverId: String,
    val id: String,
    val name: String,
)
