package com.chapterplayer.testing

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState
import com.chapterplayer.domain.playback.HistoryEntry
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.addToHistory
import com.chapterplayer.domain.playback.bookmarkTitleOrDefault
import com.chapterplayer.domain.playback.visibleBookmarks
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** An in-memory [BookRepository] for view model tests. [progress] holds every row, finished or not, like the real one. */
open class FakeBookRepository(books: List<AudioBook> = emptyList()) : BookRepository {
    val books = MutableStateFlow(books)
    val progress = MutableStateFlow<List<ReadingProgress>>(emptyList())

    override fun observeAllBooks(): Flow<List<AudioBook>> = books
    val filledDurations = mutableListOf<Map<String, Long>>()
    override suspend fun fillUnknownDurations(durations: Map<String, Long>) {
        filledDurations += durations
        books.value = books.value.map { b -> durations[b.id]?.takeIf { it > 0L && b.durationMs <= 0L }?.let { b.copy(durationMs = it) } ?: b }
    }
    override fun observeBooksByServer(serverId: String): Flow<List<AudioBook>> =
        books.map { list -> list.filter { it.serverId == serverId } }
    val collections = MutableStateFlow<List<BookCollection>>(emptyList())
    override fun observeCollections(): Flow<List<BookCollection>> = collections
    val libraries = MutableStateFlow<List<LibraryInfo>>(emptyList())
    override fun observeLibraries(): Flow<List<LibraryInfo>> = libraries
    override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> = Result.success(Unit)
    override suspend fun loadDetails(bookId: String): Result<BookDetails?> = Result.success(null)
    override suspend fun startPlayback(bookId: String): Result<PlaybackSession> = Result.failure(IllegalStateException("unused"))
    override suspend fun closePlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long): Result<Unit> = Result.success(Unit)
    /** Every [syncPlayback] call: session, position, listened time, and whether it was playing. */
    data class SyncCall(val session: PlaybackSession, val positionMs: Long, val listenedMs: Long, val isPlaying: Boolean = true)
    val syncCalls = mutableListOf<SyncCall>()
    var onSyncPlayback: suspend (SyncCall) -> Result<Unit> = { Result.success(Unit) }
    override suspend fun syncPlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long, isPlaying: Boolean): Result<Unit> {
        val call = SyncCall(session, positionMs, listenedMs, isPlaying)
        syncCalls += call
        return onSyncPlayback(call)
    }
    val fetchedProgress = mutableListOf<String>()
    var onFetchServerProgress: suspend (String) -> Result<ServerProgress?> = { Result.success(null) }
    override suspend fun fetchServerProgress(bookId: String): Result<ServerProgress?> {
        fetchedProgress += bookId
        return onFetchServerProgress(bookId)
    }
    /** The queue, with the same one-row-per-book merge as the real one (coalescePending). */
    val queued = mutableListOf<PendingProgress>()
    /** Every [queueProgress] call as given, before the merge. */
    val queueCalls = mutableListOf<PendingProgress>()
    override suspend fun queueProgress(pending: PendingProgress) {
        queueCalls += pending
        val existing = queued.firstOrNull { it.bookId == pending.bookId }
        val merged = com.chapterplayer.domain.playback.coalescePending(existing, pending)
        queued.removeAll { it.bookId == pending.bookId }
        queued += merged.copy(nextAttemptAtMs = if (merged.attempts == 0) 0L else existing?.nextAttemptAtMs ?: 0L)
    }
    override suspend fun pendingProgress(): List<PendingProgress> = queued.toList()
    /** Every call that changed the stored state, in order, e.g. "delete a" or "saveServer a". */
    val events = mutableListOf<String>()
    override suspend fun deletePending(bookId: String, ifUpdatedAtMs: Long?) {
        events += "delete $bookId"
        queued.removeAll { it.bookId == bookId && (ifUpdatedAtMs == null || it.updatedAtMs == ifUpdatedAtMs) }
    }
    val attempts = mutableListOf<Triple<String, Int, Long>>()
    override suspend fun markPendingAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long, ifUpdatedAtMs: Long?) {
        this.attempts += Triple(bookId, attempts, nextAttemptAtMs)
        val i = queued.indexOfFirst { it.bookId == bookId && (ifUpdatedAtMs == null || it.updatedAtMs == ifUpdatedAtMs) }
        if (i >= 0) queued[i] = queued[i].copy(attempts = attempts, nextAttemptAtMs = nextAttemptAtMs)
    }
    val pushed = mutableListOf<PendingProgress>()
    var onPushProgress: suspend (PendingProgress) -> Result<Unit> = { Result.success(Unit) }
    override suspend fun pushProgress(pending: PendingProgress): Result<Unit> {
        pushed += pending
        return onPushProgress(pending)
    }
    val savedServer = mutableListOf<Pair<String, ServerProgress>>()
    override suspend fun saveServerProgress(bookId: String, server: ServerProgress) {
        events += "saveServer $bookId"
        savedServer += bookId to server
    }
    val mergedServers = mutableListOf<String>()
    var onMergeAll: suspend (String) -> Unit = {}
    override suspend fun mergeAllServerProgress(serverId: String) {
        mergedServers += serverId
        onMergeAll(serverId)
    }
    override fun observeProgress(bookId: String): Flow<ReadingProgress?> =
        progress.map { list -> list.firstOrNull { it.bookId == bookId } }
    override fun observeInProgressBooks(): Flow<List<ReadingProgress>> =
        progress.map { list -> list.filter { it.positionMs > 0 && !it.isFinished }.sortedByDescending { it.lastPlayedAt } }
    override fun observeAllProgress(): Flow<List<ReadingProgress>> = progress
    val saved = mutableListOf<ReadingProgress>()
    override suspend fun saveProgress(progress: ReadingProgress) { saved += progress }

    /** Every [markFinished] call as (book, finished); [onMarkFinished] decides the result. */
    val markCalls = mutableListOf<Pair<String, Boolean>>()
    var onMarkFinished: suspend (String, Boolean) -> Result<MarkOutcome> = { _, _ -> Result.success(MarkOutcome.SYNCED) }
    /** Time of the user's finished marks, like the real repository keeps them; [markTime] is the time a mark gets. */
    val userMarks = mutableMapOf<String, Long>()
    var markTime = 800L
    override suspend fun markFinished(bookId: String, finished: Boolean): Result<MarkOutcome> {
        markCalls += bookId to finished
        val result = onMarkFinished(bookId, finished)
        if (result.isSuccess) {
            val old = progress.value.firstOrNull { it.bookId == bookId }
            val row = com.chapterplayer.domain.playback.progressAfterMark(old, bookId, finished, 100_000L, markTime)
            progress.value = progress.value.filter { it.bookId != bookId } + row
            if (finished) userMarks[bookId] = markTime else userMarks.remove(bookId)
        }
        return result
    }
    override suspend fun savePlaybackProgress(progress: ReadingProgress, loadedSinceMs: Long) {
        val existing = this.progress.value.firstOrNull { it.bookId == progress.bookId }
        val keep = com.chapterplayer.domain.playback.keepsFinishedMark(existing, userMarks[progress.bookId], loadedSinceMs)
        if (keep) userMarks[progress.bookId] = progress.lastPlayedAt else userMarks.remove(progress.bookId)
        val row = if (keep) progress.copy(isFinished = true) else progress
        saved += row
        this.progress.value = this.progress.value.filter { it.bookId != row.bookId } + row
    }

    /** Bookmark and history state for view model tests; the real rules are tested on the repository. */
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> =
        bookmarks.map { list -> visibleBookmarks(list.filter { it.bookId == bookId }) }
    override suspend fun addBookmark(bookId: String, positionMs: Long, title: String?): Bookmark {
        val bookmark = Bookmark(
            bookId, positionMs.coerceAtLeast(0L) / 1000, bookmarkTitleOrDefault(title, positionMs.coerceAtLeast(0L)), 0L, BookmarkState.PENDING_CREATE
        )
        bookmarks.value = bookmarks.value.filterNot { it.bookId == bookId && it.timeSec == bookmark.timeSec } + bookmark
        return bookmark
    }
    override suspend fun renameBookmark(bookId: String, timeSec: Long, title: String) {
        bookmarks.value = bookmarks.value.map { if (it.bookId == bookId && it.timeSec == timeSec) it.copy(title = title) else it }
    }
    override suspend fun deleteBookmark(bookId: String, timeSec: Long) {
        bookmarks.value = bookmarks.value.filterNot { it.bookId == bookId && it.timeSec == timeSec }
    }
    override fun observePendingBookmarks(): Flow<List<Bookmark>> = bookmarks.map { list -> list.filter { it.state != BookmarkState.SYNCED } }
    val pulledBookmarks = mutableListOf<String>()
    /** Every pull and push in order, e.g. "pull s1" then "push s1". */
    val bookmarkCalls = mutableListOf<String>()
    var onPullBookmarks: suspend (String) -> Result<Unit> = { Result.success(Unit) }
    override suspend fun pullBookmarks(serverId: String): Result<Unit> {
        pulledBookmarks += serverId
        bookmarkCalls += "pull $serverId"
        return onPullBookmarks(serverId)
    }
    val pushedBookmarks = mutableListOf<String>()
    var onPushBookmarks: suspend (String) -> Result<Unit> = { Result.success(Unit) }
    override suspend fun pushPendingBookmarks(serverId: String): Result<Unit> {
        pushedBookmarks += serverId
        bookmarkCalls += "push $serverId"
        return onPushBookmarks(serverId)
    }
    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    var onRecordHistory: suspend (HistoryEntry) -> Unit = {}
    val recordAttempts = mutableListOf<HistoryEntry>()
    override fun observeHistory(bookId: String): Flow<List<HistoryEntry>> = history.map { list -> list.filter { it.bookId == bookId } }
    override suspend fun recordHistory(entry: HistoryEntry) {
        recordAttempts += entry
        onRecordHistory(entry)
        history.value = addToHistory(history.value.filter { it.bookId == entry.bookId }, entry) + history.value.filter { it.bookId != entry.bookId }
    }
}

class FakeDownloadStates(initial: Map<String, DownloadState> = emptyMap()) : DownloadStates {
    val states = MutableStateFlow(initial)
    override fun observe(): Flow<Map<String, DownloadState>> = states
}

class FakeServerList(servers: List<ServerConfig> = emptyList()) : ServerRepository {
    val flow = MutableStateFlow(servers)
    override fun observeAll(): Flow<List<ServerConfig>> = flow
    override suspend fun getById(id: String) = flow.value.firstOrNull { it.id == id }
    override suspend fun save(config: ServerConfig) {}
    val deleted = mutableListOf<String>()
    override suspend fun delete(id: String) {
        deleted += id
        flow.value = flow.value.filter { it.id != id }
    }
    override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
}

fun testBook(
    id: String,
    title: String = "Title $id",
    author: String = "Author",
    narrator: String = "Narrator",
    addedAt: Long = 0L,
    seriesName: String? = null
) = AudioBook(
    id = id, title = title, author = author, narrator = narrator, durationMs = 1_000L,
    chapters = emptyList<Chapter>(), streamUrl = "", coverUrl = null,
    source = ServerSource.ABS, serverId = "s1", addedAt = addedAt, seriesName = seriesName
)

fun testServer(id: String = "s1") = ServerConfig(id, ServerSource.ABS, "Server $id", "http://$id.test", "t")

fun testSession(bookId: String, serverId: String = "s1", sessionId: String = "sess-$bookId") = PlaybackSession(
    sessionId = sessionId, serverId = serverId, bookId = bookId, durationMs = 100_000L, startPositionMs = 0L,
    tracks = emptyList(), chapters = emptyList()
)
