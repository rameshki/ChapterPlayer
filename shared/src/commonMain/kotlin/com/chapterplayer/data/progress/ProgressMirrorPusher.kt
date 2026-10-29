package com.chapterplayer.data.progress

import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.sync.NoServerStatusStore
import com.chapterplayer.data.sync.ServerStatusStore
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.mirrorPosition
import com.chapterplayer.domain.playback.mirrorPushDue
import com.chapterplayer.domain.playback.mirrorPushTargets
import com.chapterplayer.domain.playback.shouldSaveProgress
import com.chapterplayer.domain.playback.unreachableServerIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Tells the servers of a merged book's OTHER copies where listening is (merged sources, Phase 2), as a background
 * courtesy: the position mapped by fraction ([mirrorPosition]) is queued in the pending queue for each other copy
 * ([BookRepository.queueProgress]) and the [PendingProgressFlusher] delivers it (Audiobookshelf progress, Plex
 * timeline and scrobbles), with its backoff and its newest-wins rule, so a queued mirror yields to newer listening on
 * that server. No playback session is opened on the other server.
 *
 * At most once a minute per other copy while playing ([mirrorPushDue]), and once when playback pauses, stops or another
 * book takes over; never to the copy loaded in the player, nor to a server whose last sync failed ([mirrorPushTargets]).
 * Nothing happens with mirroring off ([ProgressMirrorPeers] gives no copies). Start it once with [start].
 */
class ProgressMirrorPusher(
    private val controller: PlaybackController,
    private val books: BookRepository,
    private val peers: ProgressMirrorPeers,
    private val statusStore: ServerStatusStore = NoServerStatusStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onQueued: () -> Unit = {},
    /** How often playback is looked at to see whether a push is due. */
    private val pollMs: Long = POLL_MS,
) {
    private sealed interface Event {
        data class Changed(val state: PlaybackState) : Event
        data object Tick : Event
    }

    private var started = false

    // Only the one collector in [start] touches these, so they need no lock.
    private var previous: PlaybackState? = null
    private var playingSince = 0L
    private val lastPushedAt = HashMap<String, Long>()
    private val lastPushedPosition = HashMap<String, Long>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val ticks: Flow<Event> = flow {
                while (true) {
                    delay(pollMs)
                    emit(Event.Tick)
                }
            }
            merge(controller.state.map<PlaybackState, Event> { Event.Changed(it) }, ticks).collect { event ->
                try {
                    when (event) {
                        is Event.Changed -> onChanged(event.state)
                        Event.Tick -> onTick()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing here may end the observation; the next event tries again.
                }
            }
        }
    }

    private suspend fun onChanged(current: PlaybackState) {
        val before = previous
        previous = current
        val wasPlaying = before != null && before.isPlaying
        if (wasPlaying && (!current.isPlaying || current.bookId != before!!.bookId)) {
            // Paused, stopped, released or another book took over: the last position seen is the best there is.
            val last = if (current.bookId == before.bookId) current else before
            push(before.bookId, last, isPlaying = false, loadedBookId = current.bookId)
        }
        val continuing = wasPlaying && before!!.bookId == current.bookId
        if (current.isPlaying && !continuing) playingSince = nowMs()
    }

    private suspend fun onTick() {
        val current = controller.state.value
        if (current.isPlaying) push(current.bookId, current, isPlaying = true, loadedBookId = current.bookId)
    }

    /** Queues [at] (a position of [bookId]) for each other copy that is due; [isPlaying] false for the report on pause or stop. */
    private suspend fun push(bookId: String?, at: PlaybackState, isPlaying: Boolean, loadedBookId: String?) {
        if (!shouldSaveProgress(bookId, at.positionMs, at.durationMs)) return
        val copies = peers.peersOf(bookId!!)
        if (copies.isEmpty()) return
        val unreachable = try {
            unreachableServerIds(statusStore.all.first())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        val now = nowMs()
        var queued = false
        for (peer in mirrorPushTargets(copies, loadedBookId, unreachable)) {
            // While playing, the first push waits a minute from the start (the other copy already has the start position).
            if (!mirrorPushDue(lastPushedAt[peer.bookId] ?: playingSince, now, isPlaying)) continue
            val position = mirrorPosition(at.positionMs, at.durationMs, peer.durationMs) ?: continue
            lastPushedAt[peer.bookId] = now
            if (lastPushedPosition[peer.bookId] == position) continue // nothing new to tell
            try {
                books.queueProgress(PendingProgress(peer.bookId, peer.serverId, position, peer.durationMs, listenedMs = 0L, updatedAtMs = now))
                lastPushedPosition[peer.bookId] = position
                queued = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The local rows already agree; the other server hears of it with the next push.
            }
        }
        if (queued) onQueued()
    }

    private companion object {
        const val POLL_MS = 1_000L
    }
}
