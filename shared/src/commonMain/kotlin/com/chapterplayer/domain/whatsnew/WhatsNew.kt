package com.chapterplayer.domain.whatsnew

/** One line of a release's notes: a heading ("New", "Fixed"), a bullet, or plain text. [text] may hold `**bold**` marks. */
sealed interface NoteLine {
    val text: String
    data class Heading(override val text: String) : NoteLine
    data class Bullet(override val text: String) : NoteLine
    data class Plain(override val text: String) : NoteLine
}

/** The notes of one version, as written in `CHANGELOG.md` under "## <version> (<date>)". */
data class ReleaseNotes(val version: String, val date: String?, val lines: List<NoteLine>)

/**
 * Reads the changelog text (newest version first, each under "## 0.5.4 (18 November 2026)") into notes. The title and
 * any text before the first "## " are skipped; blank lines are dropped; a line that is only `**Word**` is a heading.
 */
fun parseChangelog(text: String): List<ReleaseNotes> {
    val result = mutableListOf<ReleaseNotes>()
    var version: String? = null
    var date: String? = null
    var lines = mutableListOf<NoteLine>()
    fun finish() {
        version?.let { result += ReleaseNotes(it, date, lines) }
        lines = mutableListOf()
    }
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.startsWith("## ")) {
            finish()
            val head = line.removePrefix("## ").trim()
            val open = head.indexOf('(')
            version = (if (open >= 0) head.substring(0, open) else head).trim().ifEmpty { null }
            date = if (open >= 0 && head.endsWith(")")) head.substring(open + 1, head.length - 1).trim() else null
        } else if (version != null && line.isNotEmpty()) {
            lines += when {
                line.startsWith("- ") -> NoteLine.Bullet(line.removePrefix("- ").trim())
                line.length > 4 && line.startsWith("**") && line.endsWith("**") && !line.drop(2).dropLast(2).contains("**") ->
                    NoteLine.Heading(line.drop(2).dropLast(2))
                else -> NoteLine.Plain(line)
            }
        }
    }
    finish()
    return result
}

/** A run of text and whether it was written `**bold**`. */
data class TextRun(val text: String, val bold: Boolean)

/** Splits [text] at its `**bold**` marks; an unpaired `**` stays as plain text. */
fun splitBold(text: String): List<TextRun> {
    val runs = mutableListOf<TextRun>()
    var rest = text
    while (rest.isNotEmpty()) {
        val open = rest.indexOf("**")
        val close = if (open >= 0) rest.indexOf("**", open + 2) else -1
        if (open < 0 || close < 0) { runs += TextRun(rest, false); break }
        if (open > 0) runs += TextRun(rest.substring(0, open), false)
        val bold = rest.substring(open + 2, close)
        if (bold.isNotEmpty()) runs += TextRun(bold, true)
        rest = rest.substring(close + 2)
    }
    return runs
}

/** The release part of a version name: "0.5.4-debug.12" is "0.5.4", so a debug build does not count as a new version. */
fun releaseVersion(versionName: String): String = versionName.substringBefore('-').trim()

/**
 * The notes to show once, or null for none. [lastSeen] is the release version the person last saw notes for (null on a
 * first launch since this feature, or a fresh install). A fresh install ([hasServers] false) is not an update, so it
 * sees the first-run guidance instead; an app that already has servers sees the notes of the version it just got.
 * Nothing is shown when the changelog has no section for [current].
 */
fun notesToShow(lastSeen: String?, current: String, hasServers: Boolean, notes: List<ReleaseNotes>): ReleaseNotes? {
    if (lastSeen == current) return null
    if (lastSeen == null && !hasServers) return null
    return notes.firstOrNull { it.version == current }
}
