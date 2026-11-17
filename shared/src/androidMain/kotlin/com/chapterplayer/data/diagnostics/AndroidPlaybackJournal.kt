package com.chapterplayer.data.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.chapterplayer.domain.diagnostics.JOURNAL_LINES
import com.chapterplayer.domain.diagnostics.describeProcessExit
import com.chapterplayer.domain.diagnostics.journalEvent
import com.chapterplayer.domain.diagnostics.redact
import com.chapterplayer.domain.diagnostics.trimJournal
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where the journal is kept: the app's files folder (it is small; not a cache the system may clear at night). */
fun journalFile(context: Context): File = File(context.filesDir, "playback_journal.txt")

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH'h'mm'm'ss's'")
private val EXIT_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH'h'mm'm'")

/**
 * The journal as a text file, one line per event. It grows to twice [JOURNAL_LINES], then is rewritten with the newest
 * [JOURNAL_LINES]. Writing is best effort: a failed write never reaches playback.
 */
class FilePlaybackJournal(
    private val file: File,
    private val stamp: () -> String = { LocalDateTime.now().format(STAMP) },
) : PlaybackJournal {
    private val lock = Any()
    private var written = -1

    override fun add(event: String, positionMs: Long?) {
        val line = redact(journalEvent(event, positionMs)).replace('\n', ' ').take(200)
        synchronized(lock) {
            try {
                if (written < 0) written = readLines().size
                file.appendText("${stamp()} $line\n")
                written++
                if (written > JOURNAL_LINES * 2) {
                    file.writeText(trimJournal(readLines()).joinToString("") { it + "\n" })
                    written = JOURNAL_LINES
                }
            } catch (_: Exception) {
                // Nothing more can be done; the journal is a help, not a duty.
            }
        }
    }

    override fun load(): List<String> = synchronized(lock) { trimJournal(readLines()) }

    private fun readLines(): List<String> =
        try { if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList() } catch (_: Exception) { emptyList() }
}

/** Android's records of why this app's process ended (`ApplicationExitInfo`, Android 11 and later). */
class AndroidExitReasons(private val context: Context, private val limit: Int = 6) : ExitReasons {
    override fun recent(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, limit).map {
                val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.timestamp), ZoneId.systemDefault())
                describeProcessExit(at.format(EXIT_STAMP), it.reason, it.status, it.importance)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
