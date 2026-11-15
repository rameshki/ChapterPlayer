package com.chapterplayer.domain.diagnostics

/** One server in the problem report: its kind, whether it is signed in, and how its last sync went (no address, no name). */
data class ProblemServerLine(val kind: String, val signedIn: Boolean, val syncText: String)

/** What a problem report holds before it is redacted. */
data class ProblemReportInput(
    val appVersion: String,
    val platform: String,
    val servers: List<ProblemServerLine>,
    val queuedProgressRows: Int,
    val crashTrace: String?,
    val logLines: List<String>,
)

/** How many log lines and how many characters of a crash trace a report carries. */
const val PROBLEM_LOG_LINES = 300
const val PROBLEM_TRACE_CHARS = 6_000

/**
 * The text a tester shares when something went wrong: version, system, servers by kind with their last sync, the
 * size of the progress queue, the last crash and the last log lines. The whole text passes through [redact] (no
 * tokens, addresses, hosts, paths or long ids), and nothing is sent anywhere: the person chooses where it goes.
 */
fun buildProblemReport(input: ProblemReportInput): String {
    val text = buildString {
        appendLine("ChapterPlayer problem report")
        appendLine("App version: ${input.appVersion}")
        appendLine("System: ${input.platform}")
        appendLine()
        appendLine("Servers:")
        if (input.servers.isEmpty()) appendLine("  none")
        input.servers.forEach { appendLine("  ${it.kind}, ${if (it.signedIn) "signed in" else "signed out"}: ${it.syncText}") }
        appendLine()
        appendLine("Progress waiting to be sent: ${input.queuedProgressRows}")
        appendLine()
        appendLine("Last crash:")
        appendLine(input.crashTrace?.take(PROBLEM_TRACE_CHARS)?.trimEnd() ?: "  none saved")
        appendLine()
        appendLine("Recent events (newest last):")
        val lines = input.logLines.takeLast(PROBLEM_LOG_LINES)
        if (lines.isEmpty()) appendLine("  none")
        lines.forEach { appendLine("  $it") }
    }
    return redactKeepingFrames(text)
}

// A stack frame ("at com.chapterplayer.Foo.bar(Foo.kt:12)") holds only class, method and file names; redact() would take
// "com.chapterplayer.Foo" for a host name, so frames are set aside and put back after the rest is redacted.
private val FRAME = Regex("""(?m)^[ \t]*at [\w.$<>/-]+\((?:[\w.$-]+:\d+|Native Method|Unknown Source)\)[ \t]*$""")
private const val FRAME_MARK = "\uE002"

private fun redactKeepingFrames(text: String): String {
    val frames = ArrayList<String>()
    val masked = FRAME.replace(text) { frames += it.value; "$FRAME_MARK${frames.size - 1}$FRAME_MARK" }
    var out = redact(masked)
    frames.forEachIndexed { i, frame -> out = out.replace("$FRAME_MARK$i$FRAME_MARK", frame) }
    return out
}
