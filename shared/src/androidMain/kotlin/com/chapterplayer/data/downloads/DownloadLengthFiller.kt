package com.chapterplayer.data.downloads

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.downloads.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Finished downloads made before each file's length was recorded (Room v6) cannot be placed on a timeline offline.
 * Each time the phone comes online this asks the book's server (Audiobookshelf: `GET /api/items/{id}`, whose audioFiles
 * carry the length; Plex: the album's tracks) for the books that still have a file without one, and saves the lengths.
 * Books that have them all cost nothing. Reading a Plex book's tracks is costly (every track's detail when it has
 * chapters), so a Plex book whose server answered without the missing lengths is not asked again until the library
 * sync has written its row again ([cachedAt] changed).
 *
 * @param cachedAt when the library sync last wrote the book's row; null when unknown.
 */
class DownloadLengthFiller(
    private val dao: DownloadDao,
    private val servers: ServerRepository,
    private val sources: List<DownloadSource>,
    private val localFiles: LocalBookFiles,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val cachedAt: suspend (bookId: String) -> Long? = { null },
) {
    private var started = false

    /** Plex books whose server answered without the missing lengths, with the row's sync time at that answer. */
    private val answeredWithout = HashMap<String, Long?>()
    private val answeredGuard = Mutex()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // One pass at a time: the next online moment waits for the current pass.
            connectivity.isOnline.distinctUntilChanged().filter { it }.collect {
                try {
                    fill()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best effort: the next time the phone comes online tries again.
                }
            }
        }
    }

    /** One pass over the finished downloads; a book that fails is left for the next pass. */
    suspend fun fill() {
        val done = dao.observeAll().first().filter { it.status == DownloadStatus.DONE.name }
        for (row in done) {
            if (dao.getFiles(row.bookId).none { it.durationMs <= 0L }) continue
            try {
                val server = servers.getById(row.serverId) ?: continue
                val source = sources.forServer(server) ?: continue
                val plex = server.source == ServerSource.PLEX
                val syncedAt = if (plex) cachedAt(row.bookId) else null
                if (plex && answeredGuard.withLock { row.bookId in answeredWithout && answeredWithout[row.bookId] == syncedAt }) continue
                val lengths = source.fileLengths(server, row.bookId)
                if (lengths.isNotEmpty()) localFiles.saveDurations(row.bookId, lengths)
                if (plex) {
                    val missing = dao.getFiles(row.bookId).any { it.durationMs <= 0L && lengths[it.ino].let { ms -> ms == null || ms <= 0L } }
                    answeredGuard.withLock { if (missing) answeredWithout[row.bookId] = syncedAt else answeredWithout.remove(row.bookId) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // This book waits for the next pass; the others still get theirs.
            }
        }
    }
}
