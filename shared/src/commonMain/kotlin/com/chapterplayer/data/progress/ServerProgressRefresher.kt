package com.chapterplayer.data.progress

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Orchestrates periodic synchronization of listening progress from Audiobookshelf servers.
 * It monitors network connectivity and ensures that synchronization occurs only for valid sources
 * within the specified minimum time interval.
 */
class ServerProgressRefresher(
    private val books: BookRepository,
    private val servers: ServerRepository,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val minIntervalMs: Long = 300_000,
) {
    private val lastMergeTimes = mutableMapOf<String, Long>()
    private val mutex = Mutex()
    private var isStarted = false

    /**
     * Starts the background synchronization process if it has not been started already.
     */
    fun start() {
        if (isStarted) return
        isStarted = true

        scope.launch {
            connectivity.isOnline
                .distinctUntilChanged()
                .collect { isOnline ->
                    if (isOnline) {
                        performRefreshPass()
                    }
                }
        }
    }

    /**
     * Executes a single pass of progress synchronization for all eligible servers.
     */
    private suspend fun performRefreshPass() {
        mutex.withLock {
            val currentServers = servers.observeAll().first()
            val absServers = currentServers.filter { it.source == ServerSource.ABS }

            for (server in absServers) {
                val lastTime = lastMergeTimes[server.id]
                val currentTime = nowMs()

                if (lastTime != null && (currentTime - lastTime) < minIntervalMs) {
                    continue // Skip if the interval has not passed
                }

                lastMergeTimes[server.id] = currentTime
                try {
                    books.mergeAllServerProgress(server.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Swallow exceptions so one failing server does not block others
                }
            }
        }
    }
}
