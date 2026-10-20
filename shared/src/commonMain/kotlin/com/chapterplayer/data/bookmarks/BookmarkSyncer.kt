package com.chapterplayer.data.bookmarks

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.playback.nextBackoffMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Keeps the bookmarks of every Audiobookshelf server in step with the local ones. A pass for a server pulls first (so
 * what the server changed is merged) and then pushes what is still pending. Passes run
 *  - at app start and whenever the device comes online, with the pull at most once per [minIntervalMs] per server;
 *  - [debounceMs] after a local change (push only, no throttle);
 *  - after a failure, when the retry time ([nextBackoffMs]) of that server arrives.
 * (The library sync pulls on its own, see `LibrarySyncCoordinator`.) One pass runs at a time. A refused sign-in skips that
 * server for the pass and schedules no retry. Plex servers are skipped.
 */
class BookmarkSyncer(
    private val books: BookRepository,
    private val servers: ServerRepository,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val minIntervalMs: Long = 300_000,
    private val debounceMs: Long = 3_000,
) {
    // Guards lastPull, attempts and retries; the app scope runs on several threads. online and pendingCount are single values
    // written by one collector and read by others, so they are volatile.
    private val passLock = Mutex()
    private val lastPull = mutableMapOf<String, Long>()
    private val attempts = mutableMapOf<String, Int>()
    private val retries = mutableMapOf<String, Job>()
    private var started = false
    @Volatile private var online = false
    @Volatile private var pendingCount = 0

    fun start() {
        if (started) return
        started = true
        scope.launch {
            connectivity.isOnline.distinctUntilChanged().collect { isOnline ->
                online = isOnline
                // A separate job, so a slow server cannot delay a later online event.
                if (isOnline) scope.launch { runPass(fullPass = true, only = null, force = false) }
            }
        }
        scope.launch {
            try {
                // The pending rows themselves, not their number: an edit that keeps the number (a rename of a row whose
                // rename is on its way) is a change too.
                books.observePendingBookmarks().distinctUntilChanged().collectLatest { pending ->
                    pendingCount = pending.size
                    if (pending.isNotEmpty()) {
                        delay(debounceMs)
                        if (online) scope.launch { runPass(fullPass = false, only = null, force = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The pending rows are a trigger only; the other triggers still work.
            }
        }
    }

    private suspend fun runPass(fullPass: Boolean, only: String?, force: Boolean) {
        try {
            passLock.withLock {
                val absServers = servers.observeAll().first()
                    .filter { it.source == ServerSource.ABS && (only == null || it.id == only) }
                for (server in absServers) syncServer(server, fullPass, force)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowed: the next trigger tries again.
        }
    }

    private suspend fun syncServer(server: ServerConfig, fullPass: Boolean, force: Boolean) {
        val now = nowMs()
        val last = lastPull[server.id]
        val pull = fullPass && (force || last == null || now - last >= minIntervalMs)
        // A throttled online event still delivers what waits locally.
        val push = !fullPass || pull || pendingCount > 0
        var failed = false
        if (pull) {
            val error = attempt { books.pullBookmarks(server.id) }.exceptionOrNull()
            if (error is SignInRequiredException) return cancelRetry(server.id)
            if (error != null) failed = true else lastPull[server.id] = now
        }
        if (push) {
            val error = attempt { books.pushPendingBookmarks(server.id) }.exceptionOrNull()
            if (error is SignInRequiredException) return cancelRetry(server.id)
            if (error != null) failed = true
        }
        if (failed) {
            scheduleRetry(server.id)
        } else {
            attempts.remove(server.id)
            cancelRetry(server.id)
        }
    }

    private suspend fun attempt(call: suspend () -> Result<Unit>): Result<Unit> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun cancelRetry(serverId: String) {
        retries.remove(serverId)?.cancel()
    }

    private fun scheduleRetry(serverId: String) {
        val count = (attempts[serverId] ?: 0) + 1
        attempts[serverId] = count
        cancelRetry(serverId)
        retries[serverId] = scope.launch {
            delay(nextBackoffMs(count))
            // This job is done waiting; a failure of the pass below schedules the next one.
            passLock.withLock { retries.remove(serverId) }
            if (online) runPass(fullPass = true, only = serverId, force = true)
        }
    }
}
