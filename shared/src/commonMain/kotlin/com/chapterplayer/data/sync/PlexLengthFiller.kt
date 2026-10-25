package com.chapterplayer.data.sync

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.plex.plexRatingKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads one Plex section's album lengths (rating key to the sum of its tracks). Throws unless every page was read. */
interface PlexSectionLengths {
    suspend fun albumLengths(server: ServerConfig, sectionId: String, onProgress: (Int) -> Unit): Map<String, Long>
}

/** No Plex source (tests, a build without one): nothing is read. */
object NoPlexSectionLengths : PlexSectionLengths {
    override suspend fun albumLengths(server: ServerConfig, sectionId: String, onProgress: (Int) -> Unit): Map<String, Long> = emptyMap()
}

/**
 * Fills the lengths of Plex books that are still unknown (the album list has none; until now a length came only when a
 * book was opened). It runs after a Plex sync in the app scope, one pass per server at a time, so the library shows at
 * once and the sync never waits for it. Only sections with a book of unknown length are read, every page of their
 * tracks; a section that fails is skipped silently (the next sync tries again) and nothing partial is written. Only
 * unknown lengths are filled, so a length the book's own details gave is never replaced. Cancelling stops between
 * sections; what was written stays and the next pass starts from what is still unknown.
 */
class PlexLengthFiller(
    private val books: BookRepository,
    private val source: PlexSectionLengths = NoPlexSectionLengths,
    private val scope: CoroutineScope,
) {
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Server id to the percent read, while a pass runs for it. */
    val progress: StateFlow<Map<String, Int>> = _progress

    /** Starts a pass for [server] unless one is running; returns it (or the running one). Safe from any thread. */
    fun request(server: ServerConfig): Job {
        val candidate = scope.launch(start = CoroutineStart.LAZY) { fill(server) }
        // The state flow's update is atomic, so two callers never both start a pass.
        jobs.update { running -> if (running[server.id]?.isCompleted == false) running else running + (server.id to candidate) }
        val current = jobs.value[server.id]
        if (current !== candidate) {
            candidate.cancel()
            return current ?: candidate
        }
        candidate.invokeOnCompletion { jobs.update { running -> if (running[server.id] === candidate) running - server.id else running } }
        candidate.start()
        return candidate
    }

    /** One pass for [server]; returns when every section with an unknown length was tried. */
    suspend fun fill(server: ServerConfig) {
        if (server.source != ServerSource.PLEX) return
        val missing = try {
            books.observeBooksByServer(server.id).first().filter { it.source == ServerSource.PLEX && it.durationMs <= 0L }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val bySection = missing.filter { it.libraryId.isNotBlank() }.groupBy { it.libraryId }
        if (bySection.isEmpty()) return
        val sections = bySection.keys.toList()
        try {
            sections.forEachIndexed { index, sectionId ->
                val report = { percent: Int ->
                    val overall = (index * 100 + percent.coerceIn(0, 100)) / sections.size
                    _progress.update { it + (server.id to overall) }
                }
                report(0)
                val lengths = try {
                    source.albumLengths(server, sectionId, report)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // silent: the next sync tries again
                }
                if (lengths != null) {
                    val found = bySection.getValue(sectionId)
                        .mapNotNull { book -> lengths[plexRatingKey(book.id)]?.takeIf { it > 0L }?.let { book.id to it } }
                    for (batch in found.chunked(BATCH)) {
                        try {
                            books.fillUnknownDurations(batch.toMap())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // The next pass fills what is still unknown.
                        }
                    }
                }
            }
        } finally {
            _progress.update { it - server.id }
        }
    }

    private companion object {
        const val BATCH = 200
    }
}
