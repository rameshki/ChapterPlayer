package com.chapterplayer.ui.details

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.SourceOption
import com.chapterplayer.domain.browse.shortLabel

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
)

/** What the source sheet is choosing a copy for. */
enum class SourceAction { PLAY, DOWNLOAD }

data class SourceSheetUi(val action: SourceAction, val rows: List<SourceRow>) {
    val title: String get() = if (action == SourceAction.PLAY) "Play from" else "Download from"
}

/** One book "Merge with…" offers. */
data class MergeCandidateRow(val bookId: String, val title: String, val author: String, val typeLabel: String, val serverName: String)

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
)

/** A download can only come from an Audiobookshelf copy. */
internal fun downloadableMembers(entry: BrowseItem): List<BrowseItem> = entry.allSources.filter { it.book.source == ServerSource.ABS }

/** The rows of the "Download from" sheet: the Audiobookshelf copies, usable only when their server answers. */
internal fun downloadRows(entry: BrowseItem, rows: List<SourceRow>, context: com.chapterplayer.domain.browse.MergeContext): List<SourceRow> {
    val copies = downloadableMembers(entry).associateBy { it.book.id }
    return rows.filter { it.bookId in copies }.map { row ->
        val reachable = copies.getValue(row.bookId).book.serverId in context.onlineServerIds
        row.copy(enabled = reachable, reason = if (reachable) null else row.reason ?: "Server not reachable")
    }
}
