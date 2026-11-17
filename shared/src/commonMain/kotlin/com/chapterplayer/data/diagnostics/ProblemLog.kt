package com.chapterplayer.data.diagnostics

import com.chapterplayer.domain.diagnostics.PROBLEM_LOG_LINES
import com.chapterplayer.domain.diagnostics.redact
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The last few hundred events of this run (failed requests), kept in memory only for the "Report a problem" text.
 * Lines are redacted when they are added and again when the report is built; nothing is written to disk or sent
 * anywhere.
 */
@OptIn(ExperimentalAtomicApi::class)
object ProblemLog {
    private val lines = AtomicReference<List<String>>(emptyList())

    fun add(line: String) {
        val clean = redact(line).take(300)
        while (true) {
            val old = lines.load()
            if (lines.compareAndSet(old, (old + clean).takeLast(PROBLEM_LOG_LINES))) return
        }
    }

    fun snapshot(): List<String> = lines.load()

    fun clear() = lines.store(emptyList())
}

/** Keeps the trace of the last crash on the phone (a small file the app can read at the next start). */
interface CrashStore {
    fun save(trace: String)
    fun load(): String?
    fun clear()
}

/** No saved crash (iOS for now, and tests). */
object NoCrashStore : CrashStore {
    override fun save(trace: String) = Unit
    override fun load(): String? = null
    override fun clear() = Unit
}

/** The system line of the report: operating system and version, device model. */
internal expect fun platformDescription(): String

/**
 * A small journal of what playback did (started, paused and why, ended, errored, service created or destroyed), kept
 * on the phone across restarts so a night with a stop can be explained afterwards. Lines hold a time, an event and a
 * place in the book; never a book, server, file or address.
 */
interface PlaybackJournal {
    fun add(event: String, positionMs: Long? = null)
    /** The kept lines, oldest first. */
    fun load(): List<String>
}

/** No journal (iOS for now, and tests). */
object NoPlaybackJournal : PlaybackJournal {
    override fun add(event: String, positionMs: Long?) = Unit
    override fun load(): List<String> = emptyList()
}

/** The system's own record of why the app's process ended (Android 11 and later). */
interface ExitReasons {
    /** One line per recorded end, newest first. Empty when the system keeps none. */
    fun recent(): List<String>
}

/** No exit records (iOS, and Android before 11). */
object NoExitReasons : ExitReasons {
    override fun recent(): List<String> = emptyList()
}
