package com.chapterplayer.data.sync

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
 * [minGapMs] of the last one is skipped. Offline nothing is tried (every server would only fail and show an error)
 * and the gap is not used up; a pass skipped offline, or one that ended offline, is made as soon as the network
 * returns, even inside the gap. Pull to refresh and Sync now go to the coordinator directly and are never skipped.
 * [target] is the `LibrarySyncCoordinator` (one sync per server at a time).
 */
class ForegroundSync(
    private val target: SyncAllServers,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val minGapMs: Long = MIN_GAP_MS,
) {
    private val lock = Mutex()
    private var lastPassAtMs: Long? = null
    /** A pass was skipped offline or ended offline: the network's return makes it, gap or not. */
    private var missedOffline = false
    private var started = false

    /** Syncs now and on every later change of [network] to another network (no network is not a change to sync on). */
    fun start(network: Flow<String?>) {
        if (started) return
        started = true
        trigger(networkReturned = false)
        scope.launch {
            network.distinctUntilChanged().drop(1).filterNotNull().collect { trigger(networkReturned = true) }
        }
    }

    /** The app came to the front (iOS: `UIApplication.didBecomeActiveNotification`). */
    fun onAppActive() = trigger(networkReturned = false)

    private fun trigger(networkReturned: Boolean) {
        scope.launch {
            val due = lock.withLock {
                if (!onlineNow()) {
                    missedOffline = true
                    return@withLock false
                }
                val now = nowMs()
                val catchUp = networkReturned && missedOffline
                if (!catchUp && !foregroundSyncDue(lastPassAtMs, now, minGapMs)) return@withLock false
                lastPassAtMs = now
                missedOffline = false
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
            // Gone offline meanwhile: the servers most likely failed, so the network's return syncs again.
            if (!onlineNow()) lock.withLock { missedOffline = true }
        }
    }

    private suspend fun onlineNow(): Boolean = try {
        connectivity.isOnline.first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The monitor could not answer: try, as before.
        true
    }

    companion object {
        const val MIN_GAP_MS = 2 * 60_000L
    }
}

/** Whether a foreground pass may run at [nowMs]: none ran yet, [minGapMs] passed, or the clock went back. */
fun foregroundSyncDue(lastPassAtMs: Long?, nowMs: Long, minGapMs: Long): Boolean =
    lastPassAtMs == null || nowMs - lastPassAtMs >= minGapMs || nowMs < lastPassAtMs
