package com.chapterplayer.data.downloads

import com.chapterplayer.domain.downloads.schedulerRetryDelayMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A [DownloadScheduler] that runs each book's job in the app's own process (iOS: there is no WorkManager), with the
 * same rules: one job per book (a second schedule keeps the waiting or running one unless [schedule]'s `replace`), the
 * job waits until [allowed] says the network fits the Wi-Fi only setting and is stopped (its bytes saved, the book back
 * to QUEUED by [run]) when the network stops fitting, then waits again. A job that ends with "try again later"
 * ([BookResult.Retry]) runs again after [schedulerRetryDelayMs]. [BookDownloader] itself keeps one book downloading at
 * a time, so several scheduled books simply wait their turn.
 *
 * [active] lists the books whose download is running now (not waiting for the network or a retry): the platform holds
 * background time while it is not empty. [onFinished] hears how each job ended for good (not when cancelled).
 */
class InProcessDownloadScheduler(
    private val scope: CoroutineScope,
    private val run: suspend (bookId: String) -> BookResult,
    private val allowed: (wifiOnly: Boolean) -> Flow<Boolean>,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onFinished: suspend (bookId: String, result: BookResult) -> Unit = { _, _ -> },
) : DownloadScheduler {

    private val guard = Mutex()
    private val jobs = HashMap<String, Job>()
    private val running = MutableStateFlow<Set<String>>(emptySet())

    /** The books transferring now. */
    val active: StateFlow<Set<String>> = running.asStateFlow()

    /** Whether a job of [bookId] is scheduled (waiting or running). */
    suspend fun isScheduled(bookId: String): Boolean = guard.withLock { jobs[bookId]?.isActive == true }

    /** True when the job was new (nothing was waiting or running for this book), false when one was kept or replaced. */
    suspend fun scheduleNew(bookId: String, wifiOnly: Boolean, replace: Boolean): Boolean {
        val (job, new) = guard.withLock {
            val existing = jobs[bookId]?.takeIf { it.isActive }
            if (existing != null && !replace) return false
            // A replaced job stops and saves; BookDownloader's book lock keeps the new one waiting until it has.
            existing?.cancel()
            val job = scope.launch(start = CoroutineStart.LAZY) { loop(bookId, wifiOnly) }
            jobs[bookId] = job
            job to (existing == null)
        }
        // Started after the lock is let go: the job's own clean-up takes the same lock.
        job.start()
        return new
    }

    override suspend fun schedule(bookId: String, wifiOnly: Boolean, replace: Boolean) {
        scheduleNew(bookId, wifiOnly, replace)
    }

    override suspend fun cancel(bookId: String) {
        guard.withLock { jobs.remove(bookId) }?.cancel()
    }

    /**
     * Stops every job and waits until each has saved its bytes (their books go back to QUEUED, so the next start or
     * [DefaultDownloadController.restoreQueue] carries on). Used when iOS takes the background time away.
     */
    suspend fun stopAll() {
        val stopping = guard.withLock { jobs.values.toList().also { jobs.clear() } }
        stopping.forEach { it.cancel() }
        stopping.joinAll()
    }

    private suspend fun loop(bookId: String, wifiOnly: Boolean) {
        val self = currentCoroutineContext().job
        var attempt = 0
        try {
            while (true) {
                allowed(wifiOnly).first { it }
                val result = runWhileAllowed(bookId, wifiOnly) ?: continue // the network stopped fitting: wait again
                if (result == BookResult.Retry) {
                    attempt++
                    sleep(schedulerRetryDelayMs(attempt))
                    continue
                }
                try {
                    onFinished(bookId, result)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Only a notice; the download itself has ended.
                }
                return
            }
        } finally {
            withContext(NonCancellable) {
                guard.withLock { if (jobs[bookId] === self) jobs.remove(bookId) }
            }
        }
    }

    /** Runs the book until it ends or [allowed] turns false (then the run is stopped and null returned). */
    private suspend fun runWhileAllowed(bookId: String, wifiOnly: Boolean): BookResult? = coroutineScope {
        val work = async {
            running.update { it + bookId }
            try {
                run(bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BookResult.Retry // the downloader already turns its own failures into results; anything else is retried later
            } finally {
                running.update { it - bookId }
            }
        }
        val lost = async { allowed(wifiOnly).first { !it } }
        select {
            work.onAwait { result ->
                lost.cancel()
                result
            }
            lost.onAwait {
                work.cancel()
                null
            }
        }
    }
}

/** On app start (and on iOS when the app comes back to the front), schedules again the downloads that were waiting or running. */
class DownloadQueueRestorer(private val controller: DefaultDownloadController, private val scope: CoroutineScope) {
    fun start() {
        scope.launch {
            try {
                controller.restoreQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort: the next start tries again.
            }
        }
    }
}
