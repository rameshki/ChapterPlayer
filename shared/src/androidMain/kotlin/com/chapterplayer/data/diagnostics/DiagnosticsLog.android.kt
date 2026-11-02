package com.chapterplayer.data.diagnostics

import android.util.Log
import com.chapterplayer.domain.diagnostics.chunkText

/** The report in pieces of about 1000 characters (logcat entries are cut near 4000), between BEGIN and END markers. */
internal actual fun writeDiagnosticsLog(report: String) {
    Log.i(DIAGNOSTICS_LOG_TAG, "----- BEGIN REPORT -----")
    chunkText(report, 1000).forEach { Log.i(DIAGNOSTICS_LOG_TAG, it) }
    Log.i(DIAGNOSTICS_LOG_TAG, "----- END REPORT -----")
}
