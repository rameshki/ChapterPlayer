package com.chapterplayer.ui.details

import com.chapterplayer.domain.playback.MarkAction

/** What one row of the book long-press menu (`BookActionsSheet`) does. */
enum class BookActionKind {
    PLAY, FAVOURITE, MARK,
    /** The main download control's action (see [BookActionItem.downloadPhase]). */
    DOWNLOAD,
    /** "Download another copy" of a book several servers have. */
    DOWNLOAD_ANOTHER,
    /** Another copy's download control (see [BookActionItem.copyId]). */
    COPY_DOWNLOAD,
    SERIES, MORE_BY, DETAILS,
}

/** One row of the book long-press menu. */
data class BookActionItem(
    val kind: BookActionKind,
    val label: String,
    /** A second line: why it is disabled, the download's state, the copy it is about; null when there is nothing to add. */
    val subtitle: String? = null,
    val enabled: Boolean = true,
    /** For the download rows: the phase the action was worked out from (the tap acts on it). */
    val downloadPhase: DetailsDownloadPhase? = null,
    /** For [BookActionKind.COPY_DOWNLOAD]: the copy. */
    val copyId: String? = null,
)

/**
 * The rows of the long-press menu for a book, from the same state as its details page, so every action follows the
 * page's rules (the Play rule, the download choice of copy, offline and platform limits). Rows that do not apply are
 * left out: the download rows without downloads on this platform ([downloadsAvailable]), the series without one,
 * "More by" when the author has no other book.
 */
fun bookActionItems(state: BookDetailsUiState, downloadsAvailable: Boolean): List<BookActionItem> {
    if (state.book == null) return emptyList()
    val items = mutableListOf<BookActionItem>()
    items += BookActionItem(
        BookActionKind.PLAY,
        if (state.playback == DetailsPlayback.STARTING) "Starting…" else playButtonLabel(state.playback, state.resumePositionMs > 0L),
        subtitle = state.playingFrom ?: playCaption(state.playback, state.canPlay, state.cannotPlayReason),
        enabled = playButtonEnabled(state.playback, state.canPlay),
    )
    items += BookActionItem(BookActionKind.FAVOURITE, if (state.favourite) "Remove from favourites" else "Add to favourites")
    items += BookActionItem(BookActionKind.MARK, if (state.mark == MarkAction.MarkFinished) "Mark as finished" else "Mark as not finished")
    if (downloadsAvailable) {
        downloadItem(state.download, state.downloadCopyLabel)?.let { items += it }
        if (state.download.canDownloadAnotherCopy) items += BookActionItem(BookActionKind.DOWNLOAD_ANOTHER, "Download another copy")
        for (copy in state.otherCopyDownloads) {
            downloadItem(copy.download, copy.label)?.let { items += it.copy(kind = BookActionKind.COPY_DOWNLOAD, copyId = copy.bookId) }
        }
    }
    state.links.series?.let { items += BookActionItem(BookActionKind.SERIES, "Go to series", subtitle = it.title) }
    state.links.authors.firstOrNull()?.let { items += BookActionItem(BookActionKind.MORE_BY, "More by ${it.title}") }
    items += BookActionItem(BookActionKind.DETAILS, "Book details")
    return items
}

/** The download row for one control: Download, Try again, Pause, Resume or Remove download; null when hidden. */
private fun downloadItem(download: DetailsDownload, copyLabel: String?): BookActionItem? {
    val state = download.statusText ?: download.disabledReason
    val subtitle = listOfNotNull(copyLabel, state).joinToString(" · ").ifEmpty { null }
    fun item(label: String, enabled: Boolean = true) =
        BookActionItem(BookActionKind.DOWNLOAD, label, subtitle, enabled, downloadPhase = download.phase)
    return when (download.phase) {
        DetailsDownloadPhase.HIDDEN -> null
        DetailsDownloadPhase.IDLE -> item("Download", download.canStart)
        DetailsDownloadPhase.FAILED -> item("Try download again", download.canStart)
        DetailsDownloadPhase.QUEUED, DetailsDownloadPhase.DOWNLOADING -> item("Pause download")
        DetailsDownloadPhase.PAUSED -> item("Resume download")
        DetailsDownloadPhase.DONE -> item("Remove download")
    }
}

/** The label of the heart on the details page. */
internal fun favouriteLabel(favourite: Boolean): String = if (favourite) "Favourite" else "Add to favourites"
