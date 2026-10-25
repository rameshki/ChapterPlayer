package com.chapterplayer.data.stats

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.source.LibrarySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a refresh of the servers' listening history went: servers imported, failed, and waiting for a new sign-in. */
data class ImportOutcome(val imported: Int = 0, val failed: Int = 0, val signInRequired: Int = 0)

/** Brings each server's own listening history into the statistics. */
interface ListeningImport {
    /** Imports every server that has never been imported, now and whenever one is added. */
    fun start()

    /** Imports every server that keeps a history again, now. */
    suspend fun refresh(): ImportOutcome
}

/**
 * Imports Audiobookshelf's listening history (`/api/me/listening-stats`) so the charts do not start empty. The first
 * import of each server runs on its own (at start, and when a server is added); later ones only when the stats page
 * asks ([refresh]). A failed first import is tried again at the next start. Only sources with a history take part.
 */
class ListeningStatsImporter(
    private val servers: ServerRepository,
    private val sources: List<LibrarySource>,
    private val log: ListeningLog,
    private val scope: CoroutineScope,
    private val clock: ListeningClock,
) : ListeningImport {

    private val lock = Mutex()
    private var started = false
    /** Servers whose first import was tried in this process, so a failure does not retry on every list change. */
    private val tried = HashSet<String>()

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            servers.observeAll().collect { list ->
                try {
                    lock.withLock {
                        val done = log.importedServerIds()
                        for (server in list) {
                            if (!keepsHistory(server) || server.id in done || !tried.add(server.id)) continue
                            importOne(server)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The next start tries again.
                }
            }
        }
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
