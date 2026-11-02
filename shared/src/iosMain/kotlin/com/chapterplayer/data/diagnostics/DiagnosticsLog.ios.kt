package com.chapterplayer.data.diagnostics

import platform.Foundation.NSLog

/** The whole report as one console entry with the tag in front. */
internal actual fun writeDiagnosticsLog(report: String) {
    NSLog("%@", "$DIAGNOSTICS_LOG_TAG\n----- BEGIN REPORT -----\n$report\n----- END REPORT -----")
}
