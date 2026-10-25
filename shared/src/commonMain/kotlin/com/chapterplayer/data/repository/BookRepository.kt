package com.chapterplayer.data.repository

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.HistoryEntry
import com.chapterplayer.domain.playback.MarkOutcome
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

    /**
     * Stores the lengths found for books whose length is still unknown (0); a book with a known length keeps it, and
     * a length of 0 or less is ignored. Used by the Plex album-length pass after a sync.
     */
    suspend fun fillUnknownDurations(durations: Map<String, Long>) {}

    /** Live stream of every cached collection; each lists its book ids in server order. */
    fun observeCollections(): Flow<List<BookCollection>>

    /** Live stream of every cached library across all servers. */
    fun observeLibraries(): Flow<List<LibraryInfo>>

    /**
     * Fetch fresh books from the given server's API and update the local cache.
     * Returns a [Result] so callers can surface network errors to the UI. When only some of the server's libraries
     * could be read, the others are stored and the result is a failure naming the ones that were not.
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

    /**
     * Tells the server where [session] is (position, and the time listened since the last sync, in milliseconds); [isPlaying]
     * is false for the report made when playback paused or stopped. A failure is returned, never thrown; cancellation propagates.
     */
    suspend fun syncPlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long, isPlaying: Boolean = true): Result<Unit>

    /** The server's progress for [bookId]: success with null when it has none. A failure (including an unknown book or server) is returned; cancellation propagates. */
    suspend fun fetchServerProgress(bookId: String): Result<ServerProgress?>

    /** Queues [pending] for later delivery: one row per book, merged with the stored row by `coalescePending` in one transaction. */
    suspend fun queueProgress(pending: PendingProgress)

    /** Every queued row, oldest position first. */
    suspend fun pendingProgress(): List<PendingProgress>

    /**
     * Removes the queued row of [bookId], if any. With [ifUpdatedAtMs] only when the row still has that time:
     * a position queued again while the old one was being delivered survives.
     */
    suspend fun deletePending(bookId: String, ifUpdatedAtMs: Long? = null)

    /**
     * Records a failed delivery of the queued row of [bookId]: the attempt count and the earliest time to try again.
     * With [ifUpdatedAtMs] only when the row still has that time.
     */
    suspend fun markPendingAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long, ifUpdatedAtMs: Long? = null)

    /** Delivers a queued position to its server without a session. A failure is returned, never thrown; cancellation propagates. */
    suspend fun pushProgress(pending: PendingProgress): Result<Unit>

    /** Stores [server]'s progress for [bookId] locally unless the local row is as new or newer, or the book has a queued row. */
    suspend fun saveServerProgress(bookId: String, server: ServerProgress)

    /**
     * Best effort, for the library sync: stores the server's progress for every cached book of [serverId] that has
     * a place for it (see [saveServerProgress]). Never throws except for cancellation.
     */
    suspend fun mergeAllServerProgress(serverId: String)

    // ---- Bookmarks (local first, synced with Audiobookshelf) and position history (local only) ----

    /** The book's bookmarks without the ones waiting to be deleted, by time. */
    fun observeBookmarks(bookId: String): Flow<List<Bookmark>>

    /**
     * Saves a bookmark at [positionMs] (whole seconds, never negative) for the book; a blank or null [title] gets the default one.
     * A bookmark in the same second as an existing one replaces its title. Works offline; the server hears of it later.
     * Fails for a book that is not in the library.
     */
    suspend fun addBookmark(bookId: String, positionMs: Long, title: String?): Bookmark

    /** Renames the bookmark at [timeSec]; a blank [title] gives the default one. Does nothing for a missing bookmark or one waiting to be deleted. */
    suspend fun renameBookmark(bookId: String, timeSec: Long, title: String)

    /** Deletes the bookmark at [timeSec]: one the server never heard of is removed outright, a synced one is removed on the server later. */
    suspend fun deleteBookmark(bookId: String, timeSec: Long)

    /**
     * Merges the server's bookmarks into the local rows (see `mergeBookmarks`), book by book, each book in one transaction.
     * Pending local changes are kept, not pushed here. Does nothing for a server type without bookmarks.
     * A failure (including a sign-in needed again) is returned and leaves the rows as they were; cancellation propagates.
     */
    suspend fun pullBookmarks(serverId: String): Result<Unit>

    /**
     * Delivers the server's pending creates, renames and deletes, oldest first. A row that fails stays pending and the others
     * still go; the result is a failure if any row failed. A sign-in needed again stops at once with `SignInRequiredException`.
     */
    suspend fun pushPendingBookmarks(serverId: String): Result<Unit>

    /** The bookmark rows (creates, renames, deletes) that still wait for their server; emits again whenever one of them changes. */
    fun observePendingBookmarks(): Flow<List<Bookmark>>

    /** The book's remembered positions, newest first. */
    fun observeHistory(bookId: String): Flow<List<HistoryEntry>>

    /** Remembers [entry] with the rules of `addToHistory` (newest first, near duplicates replaced, trimmed), in one transaction. */
    suspend fun recordHistory(entry: HistoryEntry)

    /** Progress tracking */
    fun observeProgress(bookId: String): Flow<ReadingProgress?>
    fun observeInProgressBooks(): Flow<List<ReadingProgress>>

    /** Progress of every book that has any, including finished ones. */
    fun observeAllProgress(): Flow<List<ReadingProgress>>
    suspend fun saveProgress(progress: ReadingProgress)

    /**
     * The player's own save of the loaded book ([loadedSinceMs]: when it became the loaded one). Reads the stored row and writes in
     * one step that cannot interleave with [markFinished]; a finished mark the user made while the book was loaded is kept
     * (see `keepsFinishedMark`), anything else is written as given.
     */
    suspend fun savePlaybackProgress(progress: ReadingProgress, loadedSinceMs: Long)

    /**
     * Marks the book as finished (position kept) or not finished (starts over at 0). The local progress changes first, so
     * the UI follows at once and it works offline; then an Audiobookshelf server is told and its answer is adopted. A server
     * that cannot be reached gets the mark through the pending queue later ([MarkOutcome.QUEUED]); a book without server
     * progress changes locally only. Fails (changing nothing) for a book that is not in the library; cancellation propagates.
     */
    suspend fun markFinished(bookId: String, finished: Boolean): Result<MarkOutcome>
}
