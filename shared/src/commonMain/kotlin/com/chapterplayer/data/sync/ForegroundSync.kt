package com.chapterplayer.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Syncs every server that is not syncing already (`LibrarySyncCoordinator.syncAll`). */
fun interface SyncAllServers {
    suspend fun syncAll()
}

/**
 * Keeps the library fresh while the app is in front, for a platform without background sync (iOS for now): every
 * server syncs at start, when the app becomes active again and when the device moves to another network. Those
 * triggers come close together (iOS reports "active" right after launch, a network can flap), so a pass within
 * [minGapMs] of the last one is skipped. Pull to refresh and Sync now go to the coordinator directly and are never
 * skipped. [target] is the `LibrarySyncCoordinator` (one sync per server at a time).
 */
class ForegroundSync(
    private val target: SyncAllServers,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val minGapMs: Long = MIN_GAP_MS,
) {
    private val lock = Mutex()
    private var lastPassAtMs: Long? = null
    private var started = false

    /** Syncs now and on every later change of [network] to another network (no network is not a change to sync on). */
    fun start(network: Flow<String?>) {
        if (started) return
        started = true
        trigger()
        scope.launch {
            network.distinctUntilChanged().drop(1).filterNotNull().collect { trigger() }
        }
    }

    /** The app came to the front (iOS: `UIApplication.didBecomeActiveNotification`). */
    fun onAppActive() = trigger()

    private fun trigger() {
        scope.launch {
            val due = lock.withLock {
                val now = nowMs()
                if (!foregroundSyncDue(lastPassAtMs, now, minGapMs)) return@withLock false
                lastPassAtMs = now
                true
            }
            if (!due) return@launch
            try {
                target.syncAll()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The coordinator reports its own failures; a trigger never takes the app scope down.
            }
        }
    }

    companion object {
        const val MIN_GAP_MS = 2 * 60_000L
    }
}

/** Whether a foreground pass may run at [nowMs]: none ran yet, [minGapMs] passed, or the clock went back. */
fun foregroundSyncDue(lastPassAtMs: Long?, nowMs: Long, minGapMs: Long): Boolean =
    lastPassAtMs == null || nowMs - lastPassAtMs >= minGapMs || nowMs < lastPassAtMs
