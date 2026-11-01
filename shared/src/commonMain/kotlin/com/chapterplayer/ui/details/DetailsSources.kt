package com.chapterplayer.ui.details

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.SourceOption
import com.chapterplayer.domain.browse.shortLabel
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
    /** "Download this copy too" in the "Download from" sheet when another copy is on the phone already; null otherwise. */
    val hint: String? = null,
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
internal const val DOWNLOADING_TEXT = "Downloading"

/** The copies a download can come from: every copy (Audiobookshelf and Plex download alike). */
internal fun downloadableMembers(entry: BrowseItem): List<BrowseItem> = entry.allSources

/**
 * The rows of the "Download from" sheet: each copy, usable only when its server answers and it is not on the phone or
 * on its way already (a downloaded copy shows as downloaded), with its size when known ([sizes] by book id: what the
 * server said a download fetches). When a copy is on the phone, the others say "Download this copy too".
 */
internal fun downloadRows(
    entry: BrowseItem,
    rows: List<SourceRow>,
    context: com.chapterplayer.domain.browse.MergeContext,
    sizes: Map<String, Long> = emptyMap(),
): List<SourceRow> {
    val copies = downloadableMembers(entry).associateBy { it.book.id }
    val oneOnPhone = copies.values.any { it.downloadState == DownloadState.DOWNLOADED }
    return rows.filter { it.bookId in copies }.map { row ->
        val copy = copies.getValue(row.bookId)
        val reachable = copy.book.serverId in context.onlineServerIds
        val state = copy.downloadState
        row.copy(
            enabled = reachable && state == DownloadState.NOT_DOWNLOADED,
            reason = when {
                state == DownloadState.DOWNLOADED -> null
                state == DownloadState.DOWNLOADING -> DOWNLOADING_TEXT
                reachable -> null
                else -> row.reason ?: "Server not reachable"
            },
            sizeText = sizes[row.bookId]?.takeIf { it > 0L }?.let(::formatBytes),
            hint = if (oneOnPhone && state == DownloadState.NOT_DOWNLOADED && reachable) DOWNLOAD_TOO_TEXT else null,
        )
    }
}
