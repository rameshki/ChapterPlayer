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
    val top = buildString {
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
    }
    val crash = input.crashTrace?.take(PROBLEM_TRACE_CHARS)?.trimEnd()?.let(::redactTrace) ?: "  none saved"
    val bottom = buildString {
        appendLine()
        appendLine("Recent events (newest last):")
        val lines = input.logLines.takeLast(PROBLEM_LOG_LINES)
        if (lines.isEmpty()) appendLine("  none")
        lines.forEach { appendLine("  $it") }
    }
    return redact(top) + crash + "\n" + redact(bottom)
}

// A stack frame ("at com.chapterplayer.Foo.bar(Foo.kt:12)") and the class name of an exception line hold only Java
// identifiers, but redact() would take "com.chapterplayer.Foo" for a host name. Only in a crash trace they are set
// aside and put back after the rest (messages, causes' text) is redacted; the pattern allows no '/' or '-'.
private val FRAME = Regex("""(?m)^[ \t]*at (?:[A-Za-z_$][\w$]*\.)+[\w$<>]+\((?:[\w$]+\.(?:kt|java):\d+|Native Method|Unknown Source)\)[ \t]*$""")
private val EXCEPTION_TYPE = Regex("""(?m)^((?:Caused by: |Suppressed: )?(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error|Throwable))(?=:|[ \t]*$)""")
private const val FRAME_MARK = "\uE002"

/** [trace] with secrets removed but its stack frames and exception class names kept readable. */
fun redactTrace(trace: String): String {
    val kept = ArrayList<String>()
    fun keep(m: MatchResult): String { kept += m.value; return "$FRAME_MARK${kept.size - 1}$FRAME_MARK" }
    val masked = EXCEPTION_TYPE.replace(FRAME.replace(trace, ::keep)) { m -> keep(m).let { m.value.replace(m.groupValues[1], it) } }
    var out = redact(masked)
    kept.forEachIndexed { i, value -> out = out.replace("$FRAME_MARK$i$FRAME_MARK", value) }
    return out
}
