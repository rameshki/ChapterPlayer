package com.chapterplayer.data.stats

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.stats.DayZone
import com.chapterplayer.domain.stats.ListenTick
import com.chapterplayer.domain.stats.epochDayOf
import com.chapterplayer.domain.stats.listenedBetween
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The server a book is on, for the listening log. */
data class BookOwner(val serverId: String, val source: ServerSource)

/** Which server a cached book is on; null for a book the cache does not hold. */
fun interface BookOwners {
    suspend fun ownerOf(bookId: String): BookOwner?
}

/** The two clocks the recorder reads: a monotonic one for durations, and the wall clock for the day. */
interface ListeningClock {
    fun monoMs(): Long
    fun nowMs(): Long
}

/**
 * Records how long the listener listens, for the statistics page. Like the history recorder it watches the player
 * state, whatever the source and whether online or not: the time between two ticks counts when sound was playing
 * ([listenedBetween]: no seeks, no buffering, no gaps over five seconds). Time is kept per local day (the day the
 * interval began, in [zone]) and book, and written about every [flushEveryMs] while playing and at once when playback
 * pauses or stops, so a killed process loses at most that much. Parts of a second carry over to the next write.
 */
class ListeningRecorder(
    private val controller: PlaybackController,
    private val log: ListeningLog,
    private val owners: BookOwners,
    private val scope: CoroutineScope,
    private val zone: DayZone,
    private val clock: ListeningClock,
    private val flushEveryMs: Long = FLUSH_EVERY_MS,
) {
    private class Pending(var wallMs: Long = 0L, var bookMs: Long = 0L)

    private var started = false
    private var previous: ListenTick? = null
    private var previousWallMs = 0L
    private val pending = LinkedHashMap<Pair<Int, String>, Pending>()
    private val knownOwners = HashMap<String, BookOwner>()
    /** When the time not yet written began to gather; null when everything is written. */
    private var gatheringSince: Long? = null
    /** Writes that failed in a row, and the monotonic time before which no write is tried again. */
    private var failedWrites = 0
    private var retryAt = 0L

    fun start() {
        if (started) return
        started = true
        scope.launch {
            controller.state.collect { state ->
                try {
                    handle(state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failure to record must not end the recorder.
                }
            }
        }
    }

    private suspend fun handle(state: PlaybackState) {
        val mono = clock.monoMs()
        val wall = clock.nowMs()
        val tick = ListenTick(
            bookId = state.bookId,
            audible = state.isPlaying && !state.isBuffering && state.durationMs > 0L,
            positionMs = state.positionMs,
            speed = state.speed,
            monoMs = mono,
        )
        val listened = listenedBetween(previous, tick)
        val startedAtWall = previousWallMs
        previous = tick
        previousWallMs = wall
        if (listened != null) {
            val key = epochDayOf(startedAtWall, zone) to tick.bookId!!
            val entry = pending.getOrPut(key) { Pending() }
            entry.wallMs += listened.wallMs
            entry.bookMs += listened.bookMs
            if (gatheringSince == null) gatheringSince = mono
        }
        val since = gatheringSince
        if (!tick.audible || (since != null && mono - since >= flushEveryMs)) flush()
    }

    /**
     * Writes the whole seconds gathered so far; parts of a second stay for the next write. After a failed write the
     * next one waits (5 s, doubling to 5 min) so a broken database is not hit on every tick; the time is kept meanwhile.
     */
    private suspend fun flush() {
        if (clock.monoMs() < retryAt) return
        val entries = mutableListOf<ListeningLogEntry>()
        for ((key, p) in pending) {
            val wallSeconds = p.wallMs / 1_000
            val bookSeconds = p.bookMs / 1_000
            if (wallSeconds == 0L && bookSeconds == 0L) continue
            val owner = owner(key.second)
            entries += ListeningLogEntry(key.first, key.second, owner.serverId, owner.source, wallSeconds, bookSeconds)
        }
        if (entries.isNotEmpty()) {
            // On failure nothing is taken away, so the next write tries again with everything.
            try {
                log.add(entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedWrites++
                retryAt = clock.monoMs() + retryWaitMs(failedWrites)
                return
            }
            failedWrites = 0
            for (e in entries) {
                val p = pending.getValue(e.day to e.bookId)
                p.wallMs -= e.wallSeconds * 1_000
                p.bookMs -= e.bookSeconds * 1_000
            }
        }
        pending.entries.removeAll { it.value.wallMs == 0L && it.value.bookMs == 0L }
        gatheringSince = if (pending.isEmpty() || previous?.audible != true) null else clock.monoMs()
    }

    private suspend fun owner(bookId: String): BookOwner {
        knownOwners[bookId]?.let { return it }
        val found = owners.ownerOf(bookId)
        if (found != null) knownOwners[bookId] = found
        // A book the cache does not know: its id says which kind of server it came from.
        return found ?: BookOwner("", if (bookId.startsWith("plex_")) ServerSource.PLEX else ServerSource.ABS)
    }

    private companion object {
        const val FLUSH_EVERY_MS = 30_000L
        const val FIRST_RETRY_MS = 5_000L
        const val MAX_RETRY_MS = 5 * 60_000L

        fun retryWaitMs(failures: Int): Long {
            var wait = FIRST_RETRY_MS
            repeat((failures - 1).coerceAtMost(10)) { wait *= 2 }
            return wait.coerceAtMost(MAX_RETRY_MS)
        }
    }
}
