package com.chapterplayer.data.diagnostics

import android.util.Log

private const val CHUNK = 3500

/** One logcat record per line (long lines cut at 3500 characters), between BEGIN and END markers. */
internal actual fun writeDiagnosticsLog(report: String) {
    Log.i(DIAGNOSTICS_LOG_TAG, "----- BEGIN REPORT -----")
    for (line in report.lines()) {
        if (line.isEmpty()) continue
        line.chunked(CHUNK).forEach { Log.i(DIAGNOSTICS_LOG_TAG, it) }
    }
    Log.i(DIAGNOSTICS_LOG_TAG, "----- END REPORT -----")
}
