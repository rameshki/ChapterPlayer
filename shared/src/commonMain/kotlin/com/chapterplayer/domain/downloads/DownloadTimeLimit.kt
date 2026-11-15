package com.chapterplayer.domain.downloads

/**
 * Android 15 lets a dataSync foreground service (the download job) run at most 6 hours in 24. When that time is used
 * up the system stops the job (WorkManager reports `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`), and no new dataSync
 * foreground service may start until the person brings the app to the front, or until the 24 hours have passed.
 */

/**
 * How long the held downloads wait when the app is not opened: the time used counts over the last 24 hours, so 24 hours
 * after the stop every minute of it lies outside the window and the next job may run in the foreground again.
 */
const val TIME_LIMIT_HOLD_MS: Long = 24L * 60 * 60 * 1000

/** What follows when a download job stops. */
enum class AfterJobStop {
    /** Not the time limit (network lost, paused, the system's job rules), or nothing waits: WorkManager carries on. */
    NOTHING,

    /** The time limit, but the app is in front, which gives Android's budget back: the queue starts again at once. */
    CONTINUE_NOW,

    /**
     * The time limit with the app in the background: every waiting download is held (scheduled again after
     * [TIME_LIMIT_HOLD_MS]), a notification says downloads paused, and opening the app continues them.
     */
    HOLD_UNTIL_APP_OPENS,
}

/**
 * The follow-up of a stopped job. [waitingBooks] counts the downloads still waiting or running ([resumesOnStart]) after
 * the stopped job saved its bytes and put its book back in the queue; [appVisible] is whether the app is in front.
 */
fun afterJobStop(timedOut: Boolean, waitingBooks: Int, appVisible: Boolean): AfterJobStop = when {
    !timedOut || waitingBooks <= 0 -> AfterJobStop.NOTHING
    appVisible -> AfterJobStop.CONTINUE_NOW
    else -> AfterJobStop.HOLD_UNTIL_APP_OPENS
}

/** The downloads held by the time limit: those still waiting or running, never a paused, failed or finished one. */
fun heldByTimeLimit(status: DownloadStatus?): Boolean = status != null && resumesOnStart(status)

/** When the app comes to the front: a hold recorded at [heldAtMs] (null = none) is released and the queue starts again. */
fun releaseHoldOnAppOpen(heldAtMs: Long?): Boolean = heldAtMs != null

/** The notification posted when the time limit holds the downloads. Never names a book, a server or an address. */
data class TimeLimitNotice(val title: String, val text: String)

fun timeLimitNotice(waitingBooks: Int): TimeLimitNotice {
    val what = if (waitingBooks <= 1) "the download" else "$waitingBooks downloads"
    return TimeLimitNotice(
        title = "Downloads paused",
        text = "Android allows downloads in the background for 6 hours a day. Open ChapterPlayer to continue $what.",
    )
}
