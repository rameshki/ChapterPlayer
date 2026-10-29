package com.chapterplayer.data.stats

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.source.LibrarySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a refresh of the servers' listening history went: servers imported, failed, and waiting for a new sign-in. */
data class ImportOutcome(val imported: Int = 0, val failed: Int = 0, val signInRequired: Int = 0)

/** Brings each server's own listening history into the statistics. */
interface ListeningImport {
    /** Imports every server that has never been imported, now and whenever one is added; retries failures. */
    fun start()

    /** Imports every server that keeps a history again, now. */
    suspend fun refresh(): ImportOutcome
}

/**
 * Imports Audiobookshelf's listening history (`/api/me/listening-stats`) so the charts do not start empty. The first
 * import of each server runs on its own (at start, and when a server is added); later ones only when the stats page
 * asks ([refresh]). A failed first import is retried with a growing wait (30 s doubling to 30 min), at once when the
 * server's row changes (a new sign-in, a new address) or the network comes back, and never while offline or while the
 * server waits for a sign-in. Only sources with a history take part.
 */
class ListeningStatsImporter(
    private val servers: ServerRepository,
    private val sources: List<LibrarySource>,
    private val log: ListeningLog,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val clock: ListeningClock,
) : ListeningImport {

    /** A first import that failed: the row it was tried with, how often, and when to try again (monotonic ms). */
    private class Failure(val config: ServerConfig, val attempts: Int, val retryAtMs: Long)

    private val lock = Mutex()
    private var started = false
    private val failures = HashMap<String, Failure>()

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            var wasOnline: Boolean? = null
            // A new list or a network change cancels a pending wait and looks again at once.
            combine(servers.observeAll(), connectivity.isOnline) { list, online -> list to online }.collectLatest { (list, online) ->
                val cameOnline = online && wasOnline == false
                wasOnline = online
                if (!online) return@collectLatest
                var ignoreWait = cameOnline
                while (true) {
                    val wait = try {
                        lock.withLock { importFirstTimes(list, ignoreWait) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The database could not be read: look again a little later.
                        FIRST_RETRY_MS
                    }
                    ignoreWait = false
                    if (wait == null) break
                    delay(wait)
                }
            }
        }
    }

    /**
     * Imports each server not yet imported whose retry is due (all of them with [ignoreWait]); the time until the
     * next retry falls due, or null when nothing waits.
     */
    private suspend fun importFirstTimes(list: List<ServerConfig>, ignoreWait: Boolean): Long? {
        val done = log.importedServerIds()
        failures.keys.retainAll(list.mapTo(HashSet()) { it.id })
        var next: Long? = null
        for (server in list) {
            if (!keepsHistory(server) || server.id in done) {
                failures.remove(server.id)
                continue
            }
            // A server waiting for a sign-in is tried when its row changes.
            if (server.needsSignIn) continue
            val failure = failures[server.id]?.takeIf { it.config == server }
            val now = clock.monoMs()
            if (failure != null && !ignoreWait && now < failure.retryAtMs) {
                next = minOf(next ?: Long.MAX_VALUE, failure.retryAtMs - now)
                continue
            }
            if (importOne(server) == Result.OK) {
                failures.remove(server.id)
            } else {
                val attempts = (failure?.attempts ?: 0) + 1
                val wait = retryWaitMs(attempts)
                failures[server.id] = Failure(server, attempts, clock.monoMs() + wait)
                next = minOf(next ?: Long.MAX_VALUE, wait)
            }
        }
        return next
    }

    override suspend fun refresh(): ImportOutcome = lock.withLock {
        var outcome = ImportOutcome()
        for (server in servers.observeAll().first()) {
            if (!keepsHistory(server)) continue
            outcome = when (importOne(server)) {
                Result.OK -> outcome.copy(imported = outcome.imported + 1)
                Result.SIGN_IN -> outcome.copy(signInRequired = outcome.signInRequired + 1)
                Result.FAILED -> outcome.copy(failed = outcome.failed + 1)
            }
        }
        outcome
    }

    private enum class Result { OK, SIGN_IN, FAILED }

    private companion object {
        const val FIRST_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 30 * 60_000L

        /** 30 s after the first failure, doubling, at most 30 min. */
        fun retryWaitMs(attempts: Int): Long {
            var wait = FIRST_RETRY_MS
            repeat((attempts - 1).coerceAtMost(10)) { wait *= 2 }
            return wait.coerceAtMost(MAX_RETRY_MS)
        }
    }

    private fun sourceFor(server: ServerConfig): LibrarySource? = sources.firstOrNull { it.type == server.source }

    private fun keepsHistory(server: ServerConfig): Boolean = server.source == ServerSource.ABS && sourceFor(server) != null

    private suspend fun importOne(server: ServerConfig): Result {
        if (server.needsSignIn) return Result.SIGN_IN
        return try {
            val stats = sourceFor(server)?.fetchListeningStats(server) ?: return Result.FAILED
            log.replaceImport(server.id, stats, clock.nowMs())
            Result.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInRequiredException) {
            Result.SIGN_IN
        } catch (e: Exception) {
            Result.FAILED
        }
    }
}
