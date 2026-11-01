package com.chapterplayer.domain.downloads

/*
 * The decisions of a scheduler that runs downloads in the app's own process (iOS): when to retry a job that gave up for
 * now, when to hold background time, when to ask iOS 26+ for a continued processing task, what that task shows, and
 * which end of a download is worth a local notification. Pure, so they are tested on every platform.
 */

/** The wait before a job that ended with "try again later" runs again: 30 s doubling per attempt, at most 5 hours (WorkManager's rule). */
fun schedulerRetryDelayMs(attempt: Int): Long {
    var ms = 30_000L
    repeat(attempt.coerceAtLeast(1) - 1) {
        ms *= 2
        if (ms >= MAX_RETRY_DELAY_MS) return MAX_RETRY_DELAY_MS
    }
    return ms
}

private const val MAX_RETRY_DELAY_MS = 5L * 60 * 60 * 1000

/** Whether a download may run on the network as it is now under the Wi-Fi only setting. Metered: mobile data, a hotspot, Low Data Mode. */
fun downloadAllowedOn(wifiOnly: Boolean, online: Boolean, metered: Boolean): Boolean =
    mayDownload(networkPolicy(wifiOnly), onUnmetered = !metered, online = online)

enum class BackgroundTimeStep { BEGIN, END, NONE }

/**
 * Whether to begin or end the app's background time (`UIApplication.beginBackgroundTask`). It is held only while the
 * app is in the background with a download transferring; it ends as soon as either stops, so iOS never cuts it short
 * for nothing.
 */
fun backgroundTimeStep(inBackground: Boolean, downloading: Boolean, holding: Boolean): BackgroundTimeStep = when {
    !holding && inBackground && downloading -> BackgroundTimeStep.BEGIN
    holding && (!inBackground || !downloading) -> BackgroundTimeStep.END
    else -> BackgroundTimeStep.NONE
}

/**
 * Whether to ask for a continued processing task (iOS 26+) now: only for a download the user just started or resumed
 * while the app is in front (iOS allows it only then), when the system offers the API and no such task already runs.
 */
fun shouldRequestContinuedTask(appActive: Boolean, newJob: Boolean, apiAvailable: Boolean, taskRunning: Boolean): Boolean =
    appActive && newJob && apiAvailable && !taskRunning

/** What the continued processing task shows: a title, a subtitle and the progress in thousandths. */
data class ContinuedTaskText(val title: String, val subtitle: String, val completedPerMille: Long)

/**
 * The continued processing task's text for the books downloading now: the book's title ("2 books" for several), the
 * notification line ("42% · 3h 10m left") and the average progress. [titles] and [progress] are by book id.
 */
fun continuedTaskText(activeBookIds: List<String>, titles: Map<String, String>, progress: Map<String, BookProgress>): ContinuedTaskText {
    val title = when (activeBookIds.size) {
        0 -> "Downloads"
        1 -> titles[activeBookIds.first()]?.takeIf { it.isNotBlank() } ?: "Audiobook"
        else -> "${activeBookIds.size} books"
    }
    val known = activeBookIds.mapNotNull { progress[it] }
    val fraction = if (activeBookIds.isEmpty()) 0f else activeBookIds.sumOf { (progress[it]?.fraction ?: 0f).toDouble() }.toFloat() / activeBookIds.size
    val subtitle = when {
        known.isEmpty() -> "Starting"
        activeBookIds.size == 1 -> downloadNotificationText(known.first())
        else -> "${(fraction * 100).toInt().coerceIn(0, 100)}%"
    }
    return ContinuedTaskText("Downloading $title", subtitle, (fraction * 1000).toLong().coerceIn(0L, 1000L))
}

/** How a download job ended, for the notice. */
enum class DownloadEnd { DONE, FAILED, OTHER }

/** A local notification's text. */
data class DownloadNotice(val title: String, val body: String)

/**
 * The notification for a download that ended while the app was in the background: finished or failed (with its fixed,
 * safe message). Nothing while the app is in front (the screens show it) or for a pause, a removal or a later retry.
 */
fun downloadEndNotice(bookTitle: String, end: DownloadEnd, failure: String?, appActive: Boolean): DownloadNotice? {
    if (appActive) return null
    val name = bookTitle.ifBlank { "Audiobook" }
    return when (end) {
        DownloadEnd.DONE -> DownloadNotice("Download finished", "$name is ready to play offline.")
        DownloadEnd.FAILED -> DownloadNotice("Download failed", "$name: ${failure ?: "The download failed"}")
        DownloadEnd.OTHER -> null
    }
}

/**
 * [path] moved into the app container in use now. iOS keeps an app's files when it moves its data container (after an
 * update or a reinstall over the old app) but the absolute path changes, so a path stored on an earlier launch is read
 * from the part after `.../Library/Application Support/` under [applicationSupport] (the current folder). Paths
 * outside Application Support are left as they are.
 */
fun pathInContainer(path: String, applicationSupport: String): String {
    val at = path.indexOf(APPLICATION_SUPPORT_MARKER)
    if (at < 0) return path
    val rest = path.substring(at + APPLICATION_SUPPORT_MARKER.length)
    return applicationSupport.trimEnd('/') + "/" + rest
}

private const val APPLICATION_SUPPORT_MARKER = "/Library/Application Support/"
