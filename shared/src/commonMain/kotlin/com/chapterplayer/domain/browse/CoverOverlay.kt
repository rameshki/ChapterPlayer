package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress

enum class DownloadBadgeKind { NONE, DOWNLOADED, DOWNLOADING }

/** What a cover shows on top of the art. */
data class CoverOverlay(
    val progressFraction: Float?,
    val finished: Boolean,
    val badge: DownloadBadgeKind,
    val cloud: Boolean
)

fun coverOverlay(progress: ReadingProgress?, state: DownloadState, dimmed: Boolean): CoverOverlay {
    val finished = progress?.isFinished == true
    val inProgress = progress != null && !finished && progress.positionMs > 0
    return CoverOverlay(
        progressFraction = if (inProgress) progress.fraction else null,
        finished = finished,
        badge = when (state) {
            DownloadState.DOWNLOADED -> DownloadBadgeKind.DOWNLOADED
            DownloadState.DOWNLOADING -> DownloadBadgeKind.DOWNLOADING
            DownloadState.NOT_DOWNLOADED -> DownloadBadgeKind.NONE
        },
        cloud = dimmed
    )
}
