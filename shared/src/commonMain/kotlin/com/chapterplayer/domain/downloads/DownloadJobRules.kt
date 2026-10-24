package com.chapterplayer.domain.downloads

/** The progress line of the download notification: "42% · 3h 10m left", or only the percent while the speed is unknown. */
fun downloadNotificationText(progress: BookProgress?): String {
    if (progress == null) return "Starting"
    val percent = (progress.fraction * 100).toInt().coerceIn(0, 100)
    val eta = progress.etaSeconds
    return if (eta == null || eta <= 0L) "$percent%" else "$percent% · ${formatTimeLeft(eta)} left"
}

/** Seconds as "3h 10m", "2h" or "12m", rounded up to whole minutes. */
fun formatTimeLeft(seconds: Long): String {
    val minutes = (seconds.coerceAtLeast(0L) + 59) / 60
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** The status after the job was stopped (network lost, time limit, app killed): a running download waits in the queue. */
fun statusAfterStop(current: DownloadStatus?): DownloadStatus? =
    if (current == DownloadStatus.DOWNLOADING) DownloadStatus.QUEUED else null

/** A job stopped by the system's time limit schedules itself again, unless it was paused, failed, finished or removed. */
fun reenqueueAfterStop(timedOut: Boolean, status: DownloadStatus?): Boolean =
    timedOut && (status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING)

/** After a reboot or process death, these downloads are scheduled again. */
fun resumesOnStart(status: DownloadStatus): Boolean =
    status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING

/** The status to store when a download is asked for; null means nothing to do (it is already on the phone). */
fun statusOnEnqueue(existing: DownloadStatus?): DownloadStatus? = when (existing) {
    DownloadStatus.DONE -> null
    DownloadStatus.DOWNLOADING -> DownloadStatus.DOWNLOADING
    else -> DownloadStatus.QUEUED
}

/** The status to store on resume; null when there is nothing to resume. */
fun statusOnResume(existing: DownloadStatus?): DownloadStatus? = when (existing) {
    null, DownloadStatus.DONE -> null
    DownloadStatus.DOWNLOADING -> DownloadStatus.DOWNLOADING
    else -> DownloadStatus.QUEUED
}

fun canPause(existing: DownloadStatus?): Boolean =
    existing != null && existing != DownloadStatus.DONE && existing != DownloadStatus.PAUSED

fun networkPolicy(wifiOnly: Boolean): NetworkPolicy =
    if (wifiOnly) NetworkPolicy.WIFI_ONLY else NetworkPolicy.WIFI_AND_CELLULAR

/** The stored status name read back; null for an unknown name. */
fun downloadStatusOf(name: String?): DownloadStatus? = DownloadStatus.entries.firstOrNull { it.name == name }
