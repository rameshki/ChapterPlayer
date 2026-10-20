package com.chapterplayer.data.repository

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for audiobook data.
 *
 * The strategy:
 * - Books are cached locally in Room after a successful server sync.
 * - [observeAllBooks] always reads from the local cache for instant UI updates.
 * - [syncBooksFromServer] fetches fresh data from the network and updates the cache.
 * - Progress is always local-only.
 */
interface BookRepository {

    /** Live stream of all cached books across all servers. */
    fun observeAllBooks(): Flow<List<AudioBook>>

    /** Live stream of books from a specific server. */
    fun observeBooksByServer(serverId: String): Flow<List<AudioBook>>

    /** Live stream of every cached collection; each lists its book ids in server order. */
    fun observeCollections(): Flow<List<BookCollection>>

    /** Live stream of every cached library across all servers. */
    fun observeLibraries(): Flow<List<LibraryInfo>>

    /**
     * Fetch fresh books from the given server's API and update the local cache.
     * Returns a [Result] so callers can surface network errors to the UI.
     */
    suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit>

    /**
     * Asks the book's server for its details and, when it answers, caches the chapters it returned for that
     * book only (an empty list included, so removed chapters do not linger). Success with null: the server
     * type has no details call, nothing is written. A failure (including an unknown book or server, or a
     * sign-in that is needed again) leaves the cached chapters as they were; cancellation propagates.
     */
    suspend fun loadDetails(bookId: String): Result<BookDetails?>

    /**
     * Opens a playback session for the book on its server. A failure (unknown book or server, a source that
     * cannot play, a failed call, a sign-in needed again) carries a message without tokens or addresses;
     * cancellation propagates.
     */
    suspend fun startPlayback(bookId: String): Result<PlaybackSession>

    /** Ends [session] on its server with the stop position and the time listened. Best effort: a failure is returned, never thrown; cancellation propagates. */
    suspend fun closePlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long): Result<Unit>

    /** Tells the server where [session] is (position, and the time listened since the last sync, in milliseconds). A failure is returned, never thrown; cancellation propagates. */
    suspend fun syncPlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long): Result<Unit>

    /** The server's progress for [bookId]: success with null when it has none. A failure (including an unknown book or server) is returned; cancellation propagates. */
    suspend fun fetchServerProgress(bookId: String): Result<ServerProgress?>

    /** Queues [pending] for later delivery: one row per book, merged with the stored row by `coalescePending` in one transaction. */
    suspend fun queueProgress(pending: PendingProgress)

    /** Every queued row, oldest position first. */
    suspend fun pendingProgress(): List<PendingProgress>

    /** Removes the queued row of [bookId], if any. */
    suspend fun deletePending(bookId: String)

    /** Records a failed delivery of the queued row of [bookId]: the attempt count and the earliest time to try again. */
    suspend fun markPendingAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long)

    /** Delivers a queued position to its server without a session. A failure is returned, never thrown; cancellation propagates. */
    suspend fun pushProgress(pending: PendingProgress): Result<Unit>

    /** Stores [server]'s progress for [bookId] locally unless the local row is as new or newer, or the book has a queued row. */
    suspend fun saveServerProgress(bookId: String, server: ServerProgress)

    /**
     * Best effort, for the library sync: stores the server's progress for every cached book of [serverId] that has
     * a place for it (see [saveServerProgress]). Never throws except for cancellation.
     */
    suspend fun mergeAllServerProgress(serverId: String)

    /** Progress tracking */
    fun observeProgress(bookId: String): Flow<ReadingProgress?>
    fun observeInProgressBooks(): Flow<List<ReadingProgress>>

    /** Progress of every book that has any, including finished ones. */
    fun observeAllProgress(): Flow<List<ReadingProgress>>
    suspend fun saveProgress(progress: ReadingProgress)
}
