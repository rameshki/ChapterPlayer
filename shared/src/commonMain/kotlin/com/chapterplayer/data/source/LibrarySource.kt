package com.chapterplayer.data.source

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import kotlinx.coroutines.CancellationException

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

    /**
     * Every book library on [server], including the ones the user turned off (so the app can list them),
     * but not podcast libraries. A configured library gives just that one.
     */
    suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo>

    /** The collections in the libraries [server] reads, in server order. Empty for a source without collections. */
    suspend fun fetchCollections(server: ServerConfig): List<BookCollection>

    /**
     * The details of one book ([bookId] is the app's id for it): chapters, description, genres, year, series.
     * Null for a source that has no such call (Plex gets its chapters with its tracks). Throws when the call fails.
     */
    suspend fun fetchBookDetails(server: ServerConfig, bookId: String): BookDetails? = null

    /**
     * Opens a playback session for [bookId] (the app's id): the audio files with absolute addresses, the chapters and
     * where to resume. Null for a source that cannot yet. Throws when the call fails.
     */
    suspend fun startPlayback(server: ServerConfig, bookId: String): PlaybackSession? = null

    /** Ends the session [sessionId] with the stop position and the time listened, both in milliseconds. A source without sessions does nothing. */
    suspend fun closePlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long) {}

    /** Tells the server where the open session [sessionId] is: position, time listened since the last sync and book length, in milliseconds. A source without sessions does nothing. Throws when the call fails. */
    suspend fun syncPlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long, durationMs: Long) {}

    /** The server's progress for [bookId] (the app's id); null when it has none or the source has no progress. Throws when the call fails. */
    suspend fun fetchProgress(server: ServerConfig, bookId: String): ServerProgress? = null

    /** Sets the server's progress for [bookId] without a session. A source without progress does nothing. Throws when the call fails. */
    suspend fun pushProgress(server: ServerConfig, bookId: String, positionMs: Long, durationMs: Long, isFinished: Boolean) {}

    /** The server's progress for every book, keyed by the app's book id. Empty for a source without progress. Throws when the call fails. */
    suspend fun fetchAllProgress(server: ServerConfig): Map<String, ServerProgress> = emptyMap()

    /**
     * Everything one sync stores, read in one go. A source that can share work between the three reads
     * (Audiobookshelf asks the server for its libraries once, not three times) overrides this.
     * The books must come, or this throws; a libraries or collections read that fails is null, so the caller keeps the old rows.
     */
    suspend fun fetchSnapshot(server: ServerConfig): SourceSnapshot {
        val books = fetchBooks(server)
        return SourceSnapshot(books, fetchOrNull { fetchLibraries(server) }, fetchOrNull { fetchCollections(server) })
    }
}

/** What one sync of a server fetched. [libraries] and [collections] are null when reading them failed. */
class SourceSnapshot(
    val books: List<AudioBook>,
    val libraries: List<LibraryInfo>?,
    val collections: List<BookCollection>?
)

/** The result of [call], or null when it failed (cancellation still propagates). */
suspend fun <T> fetchOrNull(call: suspend () -> T): T? =
    try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
