package com.chapterplayer.data.diagnostics

import com.chapterplayer.domain.diagnostics.chunkText
import platform.Foundation.NSLog

/** The report in pieces of about 1000 characters, each its own console entry with the tag in front. */
internal actual fun writeDiagnosticsLog(report: String) {
    val pieces = chunkText(report, 1000)
    NSLog("%@", "$DIAGNOSTICS_LOG_TAG ----- BEGIN REPORT -----")
    pieces.forEachIndexed { i, piece -> NSLog("%@", "$DIAGNOSTICS_LOG_TAG [${i + 1}/${pieces.size}]\n$piece") }
    NSLog("%@", "$DIAGNOSTICS_LOG_TAG ----- END REPORT -----")
}
