package com.chapterplayer.data.downloads

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.connectivity.downloadAllowed
import com.chapterplayer.data.permissions.IosNotificationPermission
import com.chapterplayer.domain.downloads.BackgroundTimeStep
import com.chapterplayer.domain.downloads.DownloadEnd
import com.chapterplayer.domain.downloads.backgroundTimeStep
import com.chapterplayer.domain.downloads.continuedTaskText
import com.chapterplayer.domain.downloads.downloadEndNotice
import com.chapterplayer.domain.downloads.shouldRequestContinuedTask
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.BackgroundTasks.BGContinuedProcessingTask
import platform.BackgroundTasks.BGContinuedProcessingTaskRequest
import platform.BackgroundTasks.BGContinuedProcessingTaskRequestSubmissionStrategy
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObjectProtocol

/**
 * The iPhone's download scheduler. Jobs run in the app's own process ([InProcessDownloadScheduler]: one job per book,
 * the Wi-Fi only rule from `NWPathMonitor`, retries with backoff) while the app is in front. In the background:
 * - the app holds UIKit background time (`beginBackgroundTask`) while a download transfers ([backgroundTimeStep]); when
 *   iOS takes it away, every job stops cleanly (bytes saved, books back to QUEUED) and the downloads carry on when the
 *   app is in front again ([restore]: the controller's queue restore, also run at launch);
 * - on iOS 26 and later a download the user starts or resumes in the app also asks for a continued processing task
 *   (`BGContinuedProcessingTask`, identifier `<bundle id>.downloads`, allowed in Info.plist), which lets it go on with
 *   the system's progress UI (title, progress line, percent). Refused or missing (older iOS), background time alone applies.
 * A download that finishes or fails while the app is in the background shows a local notification ([downloadEndNotice]).
 *
 * Built and [start]ed on the main thread (UIKit's state and notifications).
 */
@OptIn(ExperimentalForeignApi::class)
class IosDownloadScheduler(
    private val scope: CoroutineScope,
    private val downloader: BookDownloader,
    connectivity: ConnectivityMonitor,
    private val titleOf: suspend (bookId: String) -> String?,
    private val restore: suspend () -> Unit,
    private val notifications: IosNotificationPermission,
) : DownloadScheduler {

    private val appActive = MutableStateFlow(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    private val inBackground = MutableStateFlow(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground)

    private val jobs = InProcessDownloadScheduler(
        scope = scope,
        run = { bookId -> downloader.run(bookId) },
        allowed = { wifiOnly -> connectivity.downloadAllowed(wifiOnly) },
        onFinished = ::finished,
    )

    private var backgroundTime: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private val observers = mutableListOf<NSObjectProtocol>()
    private var started = false

    private val continued = ContinuedDownloads()

    private val titles = HashMap<String, String>()
    private val titlesGuard = Mutex()

    /** The book's title for the progress UI and notices, read once per book. */
    private suspend fun title(bookId: String): String {
        titlesGuard.withLock { titles[bookId] }?.let { return it }
        val title = titleOf(bookId).orEmpty()
        titlesGuard.withLock { titles[bookId] = title }
        return title
    }

    override suspend fun schedule(bookId: String, wifiOnly: Boolean, replace: Boolean) {
        val new = jobs.scheduleNew(bookId, wifiOnly, replace)
        if (shouldRequestContinuedTask(appActive.value, new, continued.available, continued.running)) {
            val title = title(bookId)
            withContext(Dispatchers.Main) { continued.request(title) }
        }
    }

    override suspend fun cancel(bookId: String) = jobs.cancel(bookId)

    /** Follows the app's state (main thread). */
    fun start() {
        if (started) return
        started = true
        continued.register()
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, queue) { _ ->
            appActive.value = true
            inBackground.value = false
            // Downloads stopped while in the background (time taken away, the app suspended) carry on.
            scope.launch { safely { restore() } }
        }
        observers += center.addObserverForName(UIApplicationWillResignActiveNotification, null, queue) { _ -> appActive.value = false }
        observers += center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) { _ ->
            appActive.value = false
            inBackground.value = true
        }
        scope.launch(Dispatchers.Main) {
            combine(inBackground, jobs.active) { background, active -> background to active.isNotEmpty() }.collect { (background, downloading) ->
                when (backgroundTimeStep(background, downloading, holding = backgroundTime != UIBackgroundTaskInvalid)) {
                    BackgroundTimeStep.BEGIN -> beginBackgroundTime()
                    BackgroundTimeStep.END -> endBackgroundTime()
                    BackgroundTimeStep.NONE -> Unit
                }
            }
        }
    }

    private fun beginBackgroundTime() {
        backgroundTime = UIApplication.sharedApplication.beginBackgroundTaskWithName("Downloads") {
            // iOS takes the time away: every job stops (each saves its bytes, its book goes back to QUEUED) and the time
            // is given back at once, as iOS requires; a stop that the suspension interrupts finishes when the app runs
            // again, and Room never claims more than the `.part` file holds.
            scope.launch { jobs.stopAll() }
            endBackgroundTime()
        }
    }

    private fun endBackgroundTime() {
        val id = backgroundTime
        if (id == UIBackgroundTaskInvalid) return
        backgroundTime = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(id)
    }

    private suspend fun finished(bookId: String, result: BookResult) {
        val end = when (result) {
            BookResult.Done -> DownloadEnd.DONE
            is BookResult.Failed -> DownloadEnd.FAILED
            else -> DownloadEnd.OTHER
        }
        val notice = downloadEndNotice(title(bookId), end, (result as? BookResult.Failed)?.message, appActive.value) ?: return
        notifications.post(bookId, notice)
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the next time the app comes to the front tries again.
        }
    }

    /**
     * The iOS 26+ continued processing task: asked for when the user starts a download in the app, it keeps the
     * downloads going in the background with the system's progress UI until no download transfers for a few seconds,
     * or until iOS ends it (then every job stops cleanly, like when the background time runs out). Main thread.
     */
    private inner class ContinuedDownloads {
        /**
         * `<bundle id>.downloads`, used only when Info.plist allows it (BGTaskSchedulerPermittedIdentifiers): registering
         * an identifier that is not listed stops the app, and a re-signed build may carry another bundle id.
         */
        private val identifier: String? = run {
            val bundle = NSBundle.mainBundle.bundleIdentifier ?: return@run null
            val wanted = "$bundle.downloads"
            val permitted = (NSBundle.mainBundle.objectForInfoDictionaryKey("BGTaskSchedulerPermittedIdentifiers") as? List<*>).orEmpty()
            wanted.takeIf { permitted.any { it == wanted } }
        }
        private var registered = false

        /** Whether this iOS has the API (26 and later) and the app has an identifier for it. */
        val available: Boolean = identifier != null && NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
            cValue<NSOperatingSystemVersion> {
                majorVersion = 26
                minorVersion = 0
                patchVersion = 0
            }
        )

        /** Whether a task was accepted and has not ended yet. */
        var running = false
            private set

        private var watch: Job? = null

        /** Registers the launch handler once, at app start. */
        fun register() {
            val id = identifier ?: return
            if (!available || registered) return
            registered = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(id, usingQueue = null) { task ->
                (task as? BGContinuedProcessingTask)?.let { scope.launch(Dispatchers.Main) { launched(it) } }
            }
        }

        fun request(firstTitle: String) {
            val id = identifier ?: return
            if (!available || running || !registered) return
            try {
                val request = BGContinuedProcessingTaskRequest(identifier = id, title = "Downloading ${firstTitle.ifBlank { "audiobook" }}", subtitle = "Starting")
                // Start now or not at all: a download the user is watching should not wait in a queue.
                request.strategy = BGContinuedProcessingTaskRequestSubmissionStrategy.BGContinuedProcessingTaskRequestSubmissionStrategyFail
                running = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
            } catch (e: Exception) {
                running = false // refused: background time alone applies
            }
        }

        private fun launched(task: BGContinuedProcessingTask) {
            task.expirationHandler = {
                // Like the end of background time: stop every job and hand the task back at once.
                scope.launch { jobs.stopAll() }
                if (running) {
                    running = false
                    task.setTaskCompletedWithSuccess(false)
                }
                watch?.cancel()
            }
            task.progress.totalUnitCount = 1000
            watch?.cancel()
            watch = scope.launch {
                launch {
                    // No download transferring for a few seconds (all done, or waiting for Wi-Fi): the task ends.
                    jobs.active.collectLatest { active ->
                        if (active.isEmpty()) {
                            delay(IDLE_END_MS)
                            end(task, success = true)
                        }
                    }
                }
                combine(jobs.active, downloader.observeAll()) { active, progress -> active to progress }.collect { (active, progress) ->
                    val ids = active.toList()
                    val text = continuedTaskText(ids, ids.associateWith { title(it) }, progress)
                    withContext(Dispatchers.Main) {
                        task.updateTitle(text.title, subtitle = text.subtitle)
                        task.progress.completedUnitCount = text.completedPerMille
                    }
                }
            }
        }

        private suspend fun end(task: BGContinuedProcessingTask, success: Boolean) {
            withContext(Dispatchers.Main) {
                if (!running) return@withContext
                running = false
                task.setTaskCompletedWithSuccess(success)
            }
            watch?.cancel()
        }
    }

    private companion object {
        /** No download transferring for this long ends the continued processing task. */
        const val IDLE_END_MS = 5_000L
    }
}
