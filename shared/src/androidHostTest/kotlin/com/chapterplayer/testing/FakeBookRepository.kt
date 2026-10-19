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
    override fun observeProgress(bookId: String): Flow<ReadingProgress?> =
        progress.map { list -> list.firstOrNull { it.bookId == bookId } }
    override fun observeInProgressBooks(): Flow<List<ReadingProgress>> =
        progress.map { list -> list.filter { it.positionMs > 0 && !it.isFinished }.sortedByDescending { it.lastPlayedAt } }
    override fun observeAllProgress(): Flow<List<ReadingProgress>> = progress
    val saved = mutableListOf<ReadingProgress>()
    override suspend fun saveProgress(progress: ReadingProgress) { saved += progress }
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
