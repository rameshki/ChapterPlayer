package com.chapterplayer.ui.details

import com.chapterplayer.data.downloads.DOWNLOADS_NOT_ON_IOS_TEXT
import com.chapterplayer.data.downloads.DownloadRow
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.formatBytes
import com.chapterplayer.domain.downloads.formatTimeLeft

/** Where the book's download is, as the details screen draws it. HIDDEN: no control (no book shown yet). */
enum class DetailsDownloadPhase { HIDDEN, IDLE, QUEUED, DOWNLOADING, PAUSED, FAILED, DONE }

/** The download control of Book details. */
data class DetailsDownload(
    val phase: DetailsDownloadPhase = DetailsDownloadPhase.HIDDEN,
    /** 0 to 100. */
    val percent: Int = 0,
    /** "42% · 3h 10m left", "Queued", "Paused · 42%", the error, or "Downloaded · 1.2 GB"; null when idle. */
    val statusText: String? = null,
    /** The start button's label: "Download (1.2 GB)", "Download" or "Try again". */
    val buttonLabel: String = DOWNLOAD_LABEL,
    /** Download and Try again need a connection. */
    val canStart: Boolean = false,
    /** Why [canStart] is false; null otherwise. */
    val disabledReason: String? = null,
    /** "Remove download" was tapped and waits for the confirmation. */
    val confirmingRemove: Boolean = false,
    /** A line under the start button; null when there is nothing to add. */
    val note: String? = null,
)

internal const val DOWNLOAD_LABEL = "Download"
internal const val TRY_AGAIN_LABEL = "Try again"
internal const val DOWNLOAD_NEEDS_CONNECTION_TEXT = "Needs a connection to download"
internal const val DOWNLOAD_FAILED_TEXT = "The download failed"
internal const val CALCULATING_TEXT = "Calculating"

/**
 * The control for a book whose download is [row] (null: not on the phone). Audiobookshelf and Plex books download
 * alike; a book several servers have picks its copy when Download is tapped (the source sheet or the Settings choice).
 * [knownSizeBytes] is what the server said a download fetches. Without a downloader on this platform ([available]
 * false: iOS until Stage 5 step 5) Download is off with [DOWNLOADS_NOT_ON_IOS_TEXT].
 */
internal fun detailsDownload(
    row: DownloadRow?,
    online: Boolean,
    knownSizeBytes: Long?,
    confirmingRemove: Boolean,
    available: Boolean = true,
): DetailsDownload {
    val percent = row?.let { (it.fraction * 100).toInt().coerceIn(0, 100) } ?: 0
    val needsStart = row == null || row.status == DownloadStatus.FAILED
    val canStart = online && available
    val base = DetailsDownload(
        percent = percent,
        canStart = canStart,
        disabledReason = when {
            !needsStart || canStart -> null
            !available -> DOWNLOADS_NOT_ON_IOS_TEXT
            else -> DOWNLOAD_NEEDS_CONNECTION_TEXT
        },
    )
    if (row == null) {
        val size = knownSizeBytes?.takeIf { it > 0L }
        return base.copy(
            phase = DetailsDownloadPhase.IDLE,
            buttonLabel = if (size == null) DOWNLOAD_LABEL else "$DOWNLOAD_LABEL (${formatBytes(size)})",
        )
    }
    return when (row.status) {
        DownloadStatus.QUEUED -> base.copy(
            phase = DetailsDownloadPhase.QUEUED,
            statusText = if (percent > 0) "Queued · $percent%" else "Queued",
        )
        DownloadStatus.DOWNLOADING -> {
            val eta = row.etaSeconds
            val left = if (eta == null || eta <= 0L) CALCULATING_TEXT else "${formatTimeLeft(eta)} left"
            base.copy(phase = DetailsDownloadPhase.DOWNLOADING, statusText = "$percent% · $left")
        }
        DownloadStatus.PAUSED -> base.copy(phase = DetailsDownloadPhase.PAUSED, statusText = "Paused · $percent%")
        DownloadStatus.FAILED -> base.copy(
            phase = DetailsDownloadPhase.FAILED,
            statusText = row.error ?: DOWNLOAD_FAILED_TEXT,
            buttonLabel = TRY_AGAIN_LABEL,
        )
        DownloadStatus.DONE -> base.copy(
            phase = DetailsDownloadPhase.DONE,
            percent = 100,
            statusText = "Downloaded · ${formatBytes(row.totalBytes)}",
            confirmingRemove = confirmingRemove,
        )
    }
}
