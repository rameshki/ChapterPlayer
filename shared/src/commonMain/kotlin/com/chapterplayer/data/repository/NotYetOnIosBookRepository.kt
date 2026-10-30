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
import kotlinx.coroutines.flow.flowOf

/** What a sync or any other book action says on iOS until the book repository is shared (Stage 5 step 2). */
const val NOT_YET_ON_IOS_TEXT = "Library sync is not available on iOS yet"

/**
 * The iOS stand-in for [BookRepository] (Stage 5 step 1): the real one (`BookRepositoryImpl`) is still Android-only.
 * Every list is empty and every server action fails with [NOT_YET_ON_IOS_TEXT], so the Servers page shows
 * "Sync failed: Library sync is not available on iOS yet" for Sync now while adding, editing and deleting servers work.
 * Local writes (progress, history, the pending queue) are dropped: nothing on iOS can make them yet.
 */
object NotYetOnIosBookRepository : BookRepository {

    private fun <T> notYet(): Result<T> = Result.failure(UnsupportedOperationException(NOT_YET_ON_IOS_TEXT))

    override fun observeAllBooks(): Flow<List<AudioBook>> = flowOf(emptyList())
    override fun observeBooksByServer(serverId: String): Flow<List<AudioBook>> = flowOf(emptyList())
    override fun observeCollections(): Flow<List<BookCollection>> = flowOf(emptyList())
    override fun observeLibraries(): Flow<List<LibraryInfo>> = flowOf(emptyList())

    override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> = notYet()
    override suspend fun loadDetails(bookId: String): Result<BookDetails?> = notYet()
    override suspend fun startPlayback(bookId: String): Result<PlaybackSession> = notYet()
    override suspend fun closePlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long): Result<Unit> = notYet()
    override suspend fun syncPlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long, isPlaying: Boolean): Result<Unit> = notYet()
    override suspend fun fetchServerProgress(bookId: String): Result<ServerProgress?> = notYet()

    override suspend fun queueProgress(pending: PendingProgress) {}
    override suspend fun pendingProgress(): List<PendingProgress> = emptyList()
    override suspend fun deletePending(bookId: String, ifUpdatedAtMs: Long?) {}
    override suspend fun markPendingAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long, ifUpdatedAtMs: Long?) {}
    override suspend fun pushProgress(pending: PendingProgress): Result<Unit> = notYet()
    override suspend fun saveServerProgress(bookId: String, server: ServerProgress) {}
    override suspend fun mergeAllServerProgress(serverId: String) {}

    override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> = flowOf(emptyList())
    override suspend fun addBookmark(bookId: String, positionMs: Long, title: String?): Bookmark =
        throw UnsupportedOperationException(NOT_YET_ON_IOS_TEXT)
    override suspend fun renameBookmark(bookId: String, timeSec: Long, title: String) {}
    override suspend fun deleteBookmark(bookId: String, timeSec: Long) {}
    override suspend fun pullBookmarks(serverId: String): Result<Unit> = notYet()
    override suspend fun pushPendingBookmarks(serverId: String): Result<Unit> = notYet()
    override fun observePendingBookmarks(): Flow<List<Bookmark>> = flowOf(emptyList())

    override fun observeHistory(bookId: String): Flow<List<HistoryEntry>> = flowOf(emptyList())
    override suspend fun recordHistory(entry: HistoryEntry) {}

    override fun observeProgress(bookId: String): Flow<ReadingProgress?> = flowOf(null)
    override fun observeInProgressBooks(): Flow<List<ReadingProgress>> = flowOf(emptyList())
    override fun observeAllProgress(): Flow<List<ReadingProgress>> = flowOf(emptyList())
    override suspend fun saveProgress(progress: ReadingProgress) {}
    override suspend fun savePlaybackProgress(progress: ReadingProgress, loadedSinceMs: Long) {}
    override suspend fun markFinished(bookId: String, finished: Boolean): Result<MarkOutcome> = notYet()
}
