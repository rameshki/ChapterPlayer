package com.chapterplayer.domain.downloads

/** A book's download as the UI shows it: 0 to 1 done, the rolling speed, and seconds left (null while unknown). */
data class BookProgress(val fraction: Float, val bytesPerSecond: Double, val etaSeconds: Long?)

/** The progress of a book from its files' bytes and the current speed. */
fun bookProgress(files: List<FileProgress>, bytesPerSecond: Double): BookProgress {
    val remaining = files.sumOf { (it.totalBytes - it.doneBytes).coerceAtLeast(0L) }
    return BookProgress(overallFraction(files), bytesPerSecond, etaSeconds(remaining, bytesPerSecond))
}
