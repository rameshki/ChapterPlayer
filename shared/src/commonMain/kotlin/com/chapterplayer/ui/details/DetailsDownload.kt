package com.chapterplayer.ui.details

import com.chapterplayer.data.downloads.DownloadRow
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.formatBytes
import com.chapterplayer.domain.downloads.formatTimeLeft

/** Where the book's download is, as the details screen draws it. HIDDEN: no control (a Plex book). */
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
    /** A line under the start button, e.g. why a book several servers have downloads from only one; null otherwise. */
    val note: String? = null,
)

internal const val DOWNLOAD_LABEL = "Download"
internal const val DOWNLOAD_FROM_ABS_LABEL = "Download from Audiobookshelf"
internal const val PLEX_DOWNLOADS_NOTE = "Plex downloads are not available yet, so this comes from Audiobookshelf."
internal const val TRY_AGAIN_LABEL = "Try again"
internal const val DOWNLOAD_NEEDS_CONNECTION_TEXT = "Needs a connection to download"
internal const val DOWNLOAD_FAILED_TEXT = "The download failed"
internal const val CALCULATING_TEXT = "Calculating"

/**
 * The control for a book of [source] whose download is [row] (null: not on the phone). Only
 * Audiobookshelf books can be downloaded. [knownSizeBytes] is what the server said a download fetches.
 */
internal fun detailsDownload(
    source: ServerSource,
    row: DownloadRow?,
    online: Boolean,
    knownSizeBytes: Long?,
    confirmingRemove: Boolean,
    /** The book is also on another server (Plex), which cannot download: name the source so it is clear. */
    alsoOnOtherServers: Boolean = false,
): DetailsDownload {
    if (source != ServerSource.ABS) return DetailsDownload()
    val percent = row?.let { (it.fraction * 100).toInt().coerceIn(0, 100) } ?: 0
    val needsStart = row == null || row.status == DownloadStatus.FAILED
    val canStart = online
    val base = DetailsDownload(
        percent = percent,
        canStart = canStart,
        disabledReason = if (needsStart && !canStart) DOWNLOAD_NEEDS_CONNECTION_TEXT else null,
    )
    if (row == null) {
        val size = knownSizeBytes?.takeIf { it > 0L }
        val label = if (alsoOnOtherServers) DOWNLOAD_FROM_ABS_LABEL else DOWNLOAD_LABEL
        return base.copy(
            phase = DetailsDownloadPhase.IDLE,
            buttonLabel = if (size == null) label else "$label (${formatBytes(size)})",
            note = if (alsoOnOtherServers) PLEX_DOWNLOADS_NOTE else null,
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
