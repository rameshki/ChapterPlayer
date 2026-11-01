package com.chapterplayer.ui.details

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.SourceOption
import com.chapterplayer.domain.browse.shortLabel
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.formatBytes

/** One copy of a book on several servers, as the Sources section and the source sheet show it. */
data class SourceRow(
    val bookId: String,
    /** "ABS" or "Plex". */
    val typeLabel: String,
    val serverName: String,
    val downloaded: Boolean,
    /** It can be used for the action now (downloaded or its server reachable). */
    val enabled: Boolean,
    /** Why it cannot ("No network", "Server not reachable"); null when [enabled]. */
    val reason: String?,
    /** What downloading this copy fetches ("1.2 GB"), in the "Download from" sheet when the server said; null otherwise. */
    val sizeText: String? = null,
    /** "Download this copy too", "Paused: Resume" or "Failed: Try again" in the "Download from" sheet; null otherwise. */
    val hint: String? = null,
    /** Choosing it in the "Download from" sheet resumes the copy's paused or failed download instead of a new one. */
    val resumes: Boolean = false,
)

/** What the source sheet is choosing a copy for. */
enum class SourceAction { PLAY, DOWNLOAD }

data class SourceSheetUi(val action: SourceAction, val rows: List<SourceRow>) {
    val title: String get() = if (action == SourceAction.PLAY) "Play from" else "Download from"
}

/** One book "Merge with…" offers. */
data class MergeCandidateRow(
    val bookId: String,
    val title: String,
    val author: String,
    val typeLabel: String,
    val serverName: String,
    /** Every copy of the candidate (it may already be a merged book). */
    val memberIds: List<String> = listOf(bookId),
)

data class MergeSheetUi(val query: String = "", val candidates: List<MergeCandidateRow> = emptyList())

internal fun sourceRow(option: SourceOption, serverNames: Map<String, String>): SourceRow = SourceRow(
    bookId = option.item.book.id,
    typeLabel = option.item.book.source.shortLabel(),
    serverName = serverNames[option.item.book.serverId].orEmpty(),
    downloaded = option.downloaded,
    enabled = option.playable,
    reason = option.unavailableReason,
)

internal fun candidateRow(entry: BrowseItem, serverNames: Map<String, String>): MergeCandidateRow = MergeCandidateRow(
    bookId = entry.book.id,
    title = entry.book.title,
    author = entry.book.author,
    typeLabel = entry.allSources.map { it.book.source }.distinct().joinToString(" + ") { it.shortLabel() },
    serverName = entry.allSources.mapNotNull { serverNames[it.book.serverId] }.distinct().joinToString(", "),
    memberIds = entry.allSources.map { it.book.id },
)

internal const val DOWNLOAD_TOO_TEXT = "Download this copy too"
internal const val QUEUED_TEXT = "Queued"
internal const val DOWNLOADING_TEXT = "Downloading"
internal const val PAUSED_RESUME_TEXT = "Paused: Resume"
internal const val FAILED_RETRY_TEXT = "Failed: Try again"

/** The copies a download can come from: every copy (Audiobookshelf and Plex download alike). */
internal fun downloadableMembers(entry: BrowseItem): List<BrowseItem> = entry.allSources

/**
 * The rows of the "Download from" sheet, from each copy's real download state ([statuses] by book id, from the download
 * rows; the list's own state only knows "on the phone" or "not yet"): a finished copy shows as downloaded; a queued or
 * running one says so and cannot be chosen; a paused or failed one can be chosen to go on ([SourceRow.resumes]); a copy
 * with no download can be chosen. Each needs its server to answer. Sizes ([sizes] by book id) show when the server said.
 * When a copy is on the phone, the copies with no download say "Download this copy too".
 */
internal fun downloadRows(
    entry: BrowseItem,
    rows: List<SourceRow>,
    context: com.chapterplayer.domain.browse.MergeContext,
    sizes: Map<String, Long> = emptyMap(),
    statuses: Map<String, DownloadStatus> = emptyMap(),
): List<SourceRow> {
    val copies = downloadableMembers(entry).associateBy { it.book.id }
    fun statusOf(copy: BrowseItem): DownloadStatus? = statuses[copy.book.id]
        ?: when (copy.downloadState) {
            DownloadState.DOWNLOADED -> DownloadStatus.DONE
            DownloadState.DOWNLOADING -> DownloadStatus.DOWNLOADING
            DownloadState.NOT_DOWNLOADED -> null
        }
    val oneOnPhone = copies.values.any { statusOf(it) == DownloadStatus.DONE }
    return rows.filter { it.bookId in copies }.map { row ->
        val copy = copies.getValue(row.bookId)
        val reachable = copy.book.serverId in context.onlineServerIds
        val status = statusOf(copy)
        val unreachable = if (reachable) null else row.reason ?: "Server not reachable"
        val base = row.copy(downloaded = status == DownloadStatus.DONE, sizeText = sizes[row.bookId]?.takeIf { it > 0L }?.let(::formatBytes))
        when (status) {
            DownloadStatus.DONE -> base.copy(enabled = false, reason = null)
            DownloadStatus.QUEUED -> base.copy(enabled = false, reason = QUEUED_TEXT)
            DownloadStatus.DOWNLOADING -> base.copy(enabled = false, reason = DOWNLOADING_TEXT)
            DownloadStatus.PAUSED -> base.copy(enabled = reachable, reason = unreachable, hint = PAUSED_RESUME_TEXT, resumes = true)
            DownloadStatus.FAILED -> base.copy(enabled = reachable, reason = unreachable, hint = FAILED_RETRY_TEXT, resumes = true)
            null -> base.copy(enabled = reachable, reason = unreachable, hint = if (oneOnPhone && reachable) DOWNLOAD_TOO_TEXT else null)
        }
    }
}
