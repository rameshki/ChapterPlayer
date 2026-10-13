package com.chapterplayer.data.source

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource

/**
 * Where books come from. Audiobookshelf and Plex each implement this, so the repository
 * and the screens never know which kind of server a book came from.
 *
 * Today a source does one thing: list every book a server offers. Searching, resolving a
 * stream and reporting progress are added when Stage 2 and Stage 4 give them behaviour.
 */
interface LibrarySource {

    /** The kind of server this source talks to. */
    val type: ServerSource

    /**
     * Every book on [server], across the libraries or sections it is configured to use.
     * Throws when the server cannot be reached or rejects the request.
     */
    suspend fun fetchBooks(server: ServerConfig): List<AudioBook>
}
