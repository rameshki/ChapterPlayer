package com.chapterplayer.data.model

/** One library as the Libraries check-list shows it; [enabled] is false when the user turned it off. */
data class LibraryToggle(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

/** A server with every library last seen on it, turned-off ones included. */
data class ServerLibraries(
    val server: ServerConfig,
    val libraries: List<LibraryToggle>,
)
