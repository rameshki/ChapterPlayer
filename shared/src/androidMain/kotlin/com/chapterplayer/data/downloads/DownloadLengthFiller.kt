package com.chapterplayer.data.downloads

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.dto.toAudioFileEntries
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.source.toServerItemId
import com.chapterplayer.domain.downloads.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Finished downloads made before each file's length was recorded (Room v6) cannot be placed on a timeline offline.
 * Each time the phone comes online this asks the server (`GET /api/items/{id}`, whose audioFiles carry the length)
 * for the books that still have a file without one, and saves the lengths. Books that have them all cost nothing.
 */
class DownloadLengthFiller(
    private val dao: DownloadDao,
    private val servers: ServerRepository,
    private val api: AbsApiService,
    private val authorizer: AbsAuthorizer,
    private val localFiles: LocalBookFiles,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
) {
    private var started = false

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
                val item = authorizer.authorized(server) { bearer -> api.getItem(server.baseUrl, bearer, row.bookId.toServerItemId()) }
                val lengths = item.toAudioFileEntries()
                    .mapNotNull { entry ->
                        val ino = entry.ino?.trim().orEmpty()
                        val ms = entry.durationMs ?: 0L
                        if (ino.isNotEmpty() && ms > 0L) ino to ms else null
                    }
                    .toMap()
                if (lengths.isNotEmpty()) localFiles.saveDurations(row.bookId, lengths)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // This book waits for the next pass; the others still get theirs.
            }
        }
    }
}
