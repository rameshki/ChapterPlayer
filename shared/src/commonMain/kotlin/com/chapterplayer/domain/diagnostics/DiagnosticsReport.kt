package com.chapterplayer.domain.diagnostics

import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress

/** One Plex track's listening state as the server holds it (the rating key is shortened in the report). */
data class PlexTrackProgress(
    val ratingKey: String,
    val durationMs: Long,
    val viewOffsetMs: Long?,
    val viewCount: Int,
    val lastViewedAtSec: Long?,
)

/** What reading a copy's progress from its server gave: the progress as the app would use it, and for Plex the track list behind it. */
data class ServerProgressReading(val progress: ServerProgress?, val tracks: List<PlexTrackProgress> = emptyList())

/** The state of the "Read from server" step for one copy. */
sealed interface ServerReadState {
    data object NotRead : ServerReadState
    data class Read(val reading: ServerProgressReading, val atMs: Long) : ServerReadState

    /** [message] is a safe sentence (no address, no token). */
    data class Failed(val message: String) : ServerReadState
}

/** A download as the tables hold it; [folder] is the book's folder name only. */
data class DiagDownload(
    val status: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val fileNames: List<String>,
    val folder: String?,
    val error: String?,
)

/** One copy of the book: one server's entry. */
data class DiagCopy(
    val bookId: String,
    val serverName: String,
    /** "Audiobookshelf" or "Plex". */
    val sourceLabel: String,
    val isPlex: Boolean,
    val local: ReadingProgress?,
    val server: ServerReadState,
    val pending: List<PendingProgress>,
    val download: DiagDownload?,
)

/** How the copies were grouped and which copy each source rule picks right now. */
data class DiagMerge(
    val memberBookIds: List<String>,
    /** "Not the same book" corrections that involve a member, as pairs of book ids. */
    val splits: List<Pair<String, String>>,
    /** "Merge with..." corrections that involve a member. */
    val merges: List<Pair<String, String>>,
    val downloadChoice: String,
    val playChoice: String,
    val downloadPick: String,
    val playPick: String,
    val deviceOnline: Boolean,
)

/** Everything the report says, as plain data. */
data class DiagnosticsInput(
    val title: String,
    val author: String,
    val copies: List<DiagCopy>,
    val merge: DiagMerge?,
    val generatedAtMs: Long,
)

/** Every book id the report mentions: the user's own ids that [redact] must leave alone. */
fun DiagnosticsInput.bookIds(): List<String> =
    (copies.map { it.bookId } + merge?.memberBookIds.orEmpty() +
        merge?.splits.orEmpty().flatMap { listOf(it.first, it.second) } +
        merge?.merges.orEmpty().flatMap { listOf(it.first, it.second) } +
        copies.flatMap { c -> c.pending.mapNotNull { it.mirrorOf } }).distinct()

/** The finished report text: built from [input], then passed through [redact]. */
fun buildReport(input: DiagnosticsInput): String = redact(reportText(input), keep = input.bookIds())

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

private fun reportText(input: DiagnosticsInput): String = buildString {
    appendLine("ChapterPlayer diagnostics report")
    appendLine("Generated: ${formatUtc(input.generatedAtMs)}")
    appendLine("Book: ${input.title}" + if (input.author.isNotBlank()) " by ${input.author}" else "")
    appendLine("Copies: ${input.copies.size}")
    input.copies.forEachIndexed { i, copy ->
        appendLine()
        appendLine("== Copy ${i + 1}: ${copy.serverName} (${copy.sourceLabel}) ==")
        appendLine("Book id: ${copy.bookId}")
        appendLine(localLine(copy.local))
        appendServer(copy)
        appendPending(copy)
        appendLine(downloadLine(copy.download))
    }
    input.merge?.let { appendMerge(it) }
}.trimEnd()

private fun localLine(p: ReadingProgress?): String =
    if (p == null) "Local progress: none"
    else "Local progress: position ${formatClock(p.positionMs)} (${p.positionMs / 1000L} s) of ${formatClock(p.durationMs)}, " +
        "last played ${formatUtc(p.lastPlayedAt)}, finished ${yesNo(p.isFinished)}, mirrored ${yesNo(p.mirrored)}"

private fun StringBuilder.appendServer(copy: DiagCopy) {
    when (val state = copy.server) {
        ServerReadState.NotRead -> appendLine("Server progress: not read yet (tap Read from server)")
        is ServerReadState.Failed -> appendLine("Server progress: read failed: ${state.message}")
        is ServerReadState.Read -> {
            appendLine("Server progress (read ${formatUtc(state.atMs)}):")
            val p = state.reading.progress
            if (p == null) {
                appendLine("  The server holds no progress for this book")
            } else {
                appendLine(
                    "  ${if (copy.isPlex) "Computed book position" else "Position"} ${formatClock(p.positionMs)} (${p.positionMs / 1000L} s) " +
                        "of ${formatClock(p.durationMs)}, finished ${yesNo(p.isFinished)}, updated ${formatUtc(p.updatedAtMs)}"
                )
            }
            if (copy.isPlex) {
                if (state.reading.tracks.isEmpty()) appendLine("  Tracks: none listed")
                state.reading.tracks.forEachIndexed { i, t ->
                    appendLine(
                        "  Track ${i + 1} (key ${keySuffix(t.ratingKey)}): offset ${t.viewOffsetMs?.let { formatClock(it) } ?: "none"}, " +
                            "plays ${t.viewCount}, last viewed ${formatUtc((t.lastViewedAtSec ?: 0L) * 1000L)}, length ${formatClock(t.durationMs)}"
                    )
                }
            }
        }
    }
}

private fun StringBuilder.appendPending(copy: DiagCopy) {
    if (copy.pending.isEmpty()) {
        appendLine("Pending queue: empty")
        return
    }
    appendLine("Pending queue: ${copy.pending.size} row(s)")
    for (row in copy.pending) {
        val next = if (row.nextAttemptAtMs <= 0L) "now" else formatUtc(row.nextAttemptAtMs)
        appendLine(
            "  position ${formatClock(row.positionMs)} (${row.positionMs / 1000L} s) of ${formatClock(row.durationMs)}, " +
                "listened ${row.listenedMs / 1000L} s, queued ${formatUtc(row.updatedAtMs)}, attempts ${row.attempts}, " +
                "next attempt $next, markedFinished ${row.markedFinished ?: "none"}, mirrorOf ${row.mirrorOf ?: "none"}"
        )
    }
}

private fun downloadLine(d: DiagDownload?): String {
    if (d == null) return "Download: none"
    val files = if (d.fileNames.isEmpty()) "none" else d.fileNames.joinToString(", ") { shortName(it) }
    return "Download: ${d.status}, ${formatBytes(d.doneBytes)} of ${formatBytes(d.totalBytes)}, folder ${d.folder ?: "unknown"}, " +
        "files ${d.fileNames.size}: $files" + (d.error?.let { ", error: $it" } ?: "")
}

private fun StringBuilder.appendMerge(m: DiagMerge) {
    appendLine()
    appendLine("== Merged sources ==")
    appendLine("Group members: ${m.memberBookIds.joinToString(", ")}")
    appendLine("Split corrections (not the same book): " + pairs(m.splits))
    appendLine("Merge corrections (same book): " + pairs(m.merges))
    appendLine("Phone online: ${yesNo(m.deviceOnline)}")
    appendLine("Download from: ${m.downloadChoice} -> ${m.downloadPick}")
    appendLine("Play from: ${m.playChoice} -> ${m.playPick}")
}

private fun pairs(list: List<Pair<String, String>>): String =
    if (list.isEmpty()) "none" else list.joinToString("; ") { "${it.first} / ${it.second}" }
