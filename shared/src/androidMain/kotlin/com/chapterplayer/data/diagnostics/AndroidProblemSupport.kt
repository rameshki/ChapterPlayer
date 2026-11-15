package com.chapterplayer.data.diagnostics

import android.os.Build
import com.chapterplayer.domain.diagnostics.PROBLEM_TRACE_CHARS
import com.chapterplayer.domain.diagnostics.redact
import android.content.Context
import java.io.File

internal actual fun platformDescription(): String =
    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"

/** Where the last crash is kept. */
fun crashFile(context: Context): File = File(context.cacheDir, "last_crash.txt")

/** The last crash as a small redacted text file in the cache folder (not part of a phone backup). */
class FileCrashStore(private val file: File) : CrashStore {
    override fun save(trace: String) {
        try { file.writeText(redact(trace).take(PROBLEM_TRACE_CHARS)) } catch (_: Exception) { /* nothing more can be done while crashing */ }
    }

    override fun load(): String? = try { file.takeIf { it.isFile }?.readText() } catch (_: Exception) { null }

    override fun clear() { try { file.delete() } catch (_: Exception) { } }
}

/** Saves the trace of an uncaught exception, then lets the system's own handler end the app as before. */
fun installCrashRecorder(store: CrashStore) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
            store.save("Thread ${thread.name}\n${error.stackTraceToString()}")
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }
}
