package com.chapterplayer.domain.browse

import com.chapterplayer.domain.playback.bookFraction
import com.chapterplayer.domain.playback.isFinishedAt

/**
 * Data class representing the necessary information to display a resume card for a book.
 */
data class ResumeInfo(
    /** The copy to resume: for a book on several servers the one played last (see [resumeCopy]). */
    val item: BrowseItem,
    val chapterTitle: String?,
    val fraction: Float,
    val timeLeft: String,
    /** The list entry [item] belongs to (itself for a book on one server). */
    val entry: BrowseItem = item,
)

/**
 * Finds the best candidate to resume playback from a list of browse items.
 *
 * @param items The list of items to evaluate.
 * @return A [ResumeInfo] for the most recently played unfinished book, or null if none found.
 */
fun resumeCandidate(items: List<BrowseItem>): ResumeInfo? {
    val candidate = items
        .filter {
            it.progress != null && !it.progress.isFinished && it.progress.positionMs > 0 &&
                !isFinishedAt(it.progress.positionMs, it.book.durationMs)
        }
        .sortedWith(compareByDescending<BrowseItem> { it.progress?.lastPlayedAt }.thenBy { it.book.id })
        .firstOrNull() ?: return null

    // A merged book resumes the copy that was played last, with that copy's chapters and length.
    val copy = resumeCopy(candidate)
    val progress = candidate.progress!!
    val book = copy.book

    // Determine the chapter title based on current position
    val chapterTitle = if (book.chapters.isNotEmpty()) {
        val index = currentChapterIndex(book.chapters, progress.positionMs)
        index?.let { book.chapters[it].title }
    } else {
        null
    }

    return ResumeInfo(
        item = copy.copy(progress = progress),
        entry = candidate,
        chapterTitle = chapterTitle,
        fraction = bookFraction(progress.positionMs, book.durationMs),
        timeLeft = formatTimeLeft(book.durationMs, progress.positionMs)
    )
}
