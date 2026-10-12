package com.chapterplayer.data.model

/**
 * Identifies which server a book came from.
 * Used to route API calls, display source badges, and filter the library.
 */
enum class ServerSource {
    ABS,   // Audiobookshelf
    PLEX   // Plex Media Server
}
