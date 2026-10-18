package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ReadingProgress

/**
 * Represents a single row of data for a chapter in a UI list.
 */
data class ChapterRow(val index: Int, val title: String, val startMs: Long, val durationMs: Long, val isCurrent: Boolean)

/**
 * Transforms a list of chapters into a list of UI rows, marking the current chapter based on progress.
 */
fun chapterRows(chapters: List<Chapter>, progress: ReadingProgress?): List<ChapterRow> {
    if (chapters.isEmpty()) return emptyList()

    val currentIndex = when {
        progress == null -> 0
        progress.isFinished -> chapters.size - 1
        else -> currentChapterIndex(chapters, progress.positionMs) ?: 0
    }

    return chapters.mapIndexed { index, chapter ->
        ChapterRow(
            index = index,
            title = chapter.title,
            startMs = chapter.startMs,
            durationMs = chapter.durationMs,
            isCurrent = index == currentIndex
        )
    }
}

/**
 * Contains formatted metadata about a book for display in an "About" section.
 */
data class AboutInfo(
    val description: String?,
    val genres: List<String>,
    val year: String?,
    val seriesLine: String?,
    val authorLine: String?,
    val narratorLine: String?,
    val needsConnection: Boolean
)

/**
 * Aggregates information from an AudioBook and its BookDetails into a single displayable model.
 */
fun aboutInfo(book: AudioBook, details: BookDetails?): AboutInfo {
    val needsConnection = details == null

    val description = details?.description
    val genres = details?.genres ?: emptyList()
    val year = details?.publishedYear

    val seriesLine = run {
        val seriesEntries = details?.series?.mapNotNull { ref ->
            val name = ref.name.trim()
            if (name.isEmpty()) null
            else {
                val seq = ref.sequence?.trim()
                if (seq.isNullOrBlank()) name else "$name #$seq"
            }
        }

        if (!seriesEntries.isNullOrEmpty()) {
            seriesEntries.joinToString(", ")
        } else {
            val bName = book.seriesName?.trim()
            val bSeq = book.seriesSequence?.trim()
            if (!bName.isNullOrBlank()) {
                if (!bSeq.isNullOrBlank()) "$bName #$bSeq" else bName
            } else {
                null
            }
        }
    }

    val authorLine = book.author.trim().let { if (it.isEmpty()) null else "By $it" }
    val narratorLine = book.narrator.trim().let { if (it.isEmpty()) null else "Narrated by $it" }

    return AboutInfo(
        description = description,
        genres = genres,
        year = year,
        seriesLine = seriesLine,
        authorLine = authorLine,
        narratorLine = narratorLine,
        needsConnection = needsConnection
    )
}
