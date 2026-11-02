package com.chapterplayer.domain.diagnostics

/*
 * The diagnostics report is read by a person and by Claude over a log, and may be pasted anywhere, so one last pass
 * removes everything that must never leave the phone: tokens, passwords, auth headers, addresses (URLs, hosts, IPv4,
 * IPv6), file system paths, machine ids and long opaque ids. The report builder also avoids writing these; this pass
 * is the safety net and is tested with realistic values.
 */

private const val REMOVED = "[removed]"
private const val ADDRESS = "[address]"
private const val ID = "[id]"
private const val PATH = "[path]"

private val HEADER_LINE = Regex(
    """(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\s*[:=][^\r\n]*"""
)
private val BEARER = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")
private val URL = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>)\]]+""")

// Names ending in token / key / password / secret ("accessToken", "refresh_token", "authToken"), or exactly "sig".
private val SECRET_PAIR = Regex(
    """(?i)(?<![A-Za-z0-9])([a-z_-]*(?:token|api[_-]?key|apikey|password|passwd|pwd|secret|signature)|sig)["']?\s*[=:]\s*("[^"]*"|'[^']*'|[^\s,;&"']+)"""
)
private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]*)?""")
private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
private val LONG_HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")
private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")
private val IPV6 = Regex("""(?<![\w:])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?![\w:])""")

// Files and documents are not host names even though they look like "name.ext".
private const val NOT_A_TLD =
    "mp3|m4a|m4b|m4v|mp4|flac|ogg|oga|opus|aac|wav|wma|webm|aax|aa|mka|mkv|avi|jpg|jpeg|png|gif|webp|bmp|svg|pdf|epub|mobi|" +
        "txt|json|xml|html|htm|css|js|ts|kt|kts|md|log|zip|rar|gz|tar|part|tmp|db|cue|nfo|srt|vtt|ini|yml|yaml"
private val HOST = Regex(
    """(?i)\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?!(?:$NOT_A_TLD)\b)[a-z]{2,}(?![\w-])(?!\.[a-z0-9])(?::\d{1,5})?"""
)

// A single-label host only where it is written as an address: name:port.
private val HOST_PORT = Regex("""(?i)\b(?=[a-z0-9-]*[a-z])[a-z0-9][a-z0-9-]*:\d{2,5}\b""")

// Runs of folders: /data/user/0/files/..., C:\Users\...
private val UNIX_PATH = Regex("""(?<![\w.:/])/(?:[\w.@+~-]+/)+[\w.@+~-]*""")
private val WINDOWS_PATH = Regex("""\b[A-Za-z]:\\[^\s"'<>|]*""")

// A long run of letters, digits, '-' and '_' with at least one of each; a file name ("... .mp3") is left alone.
private val OPAQUE = Regex(
    """(?=[\w-]*\d)(?=[\w-]*[A-Za-z])(?<![\w-])[\w-]{20,}(?![\w-])(?!\.(?:$NOT_A_TLD)\b)"""
)

private const val KEEP_OPEN = '\uE000'
private const val KEEP_CLOSE = '\uE001'

/**
 * [text] with secrets and addresses replaced by short markers. [keep] lists strings that are the user's own and must
 * survive untouched (the book ids shown in the report); everything else goes through every rule.
 */
fun redact(text: String, keep: Collection<String> = emptyList()): String {
    val kept = keep.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    var out = text
    kept.forEachIndexed { i, value -> out = out.replace(value, "$KEEP_OPEN$i$KEEP_CLOSE") }
    out = HEADER_LINE.replace(out) { "${it.groupValues[1]}: $REMOVED" }
    out = BEARER.replace(out, "Bearer $REMOVED")
    out = URL.replace(out, ADDRESS)
    out = SECRET_PAIR.replace(out) { "${it.groupValues[1]}=$REMOVED" }
    out = JWT.replace(out, REMOVED)
    out = IPV4.replace(out, ADDRESS)
    out = IPV6.replace(out) { m ->
        // Without "::" only the full eight-group form is an address; "1:02:03" is a time.
        if (m.value.contains("::") || m.value.count { it == ':' } == 7) ADDRESS else m.value
    }
    out = WINDOWS_PATH.replace(out, PATH)
    out = UNIX_PATH.replace(out, PATH)
    out = HOST.replace(out, ADDRESS)
    out = HOST_PORT.replace(out, ADDRESS)
    out = UUID.replace(out, ID)
    out = LONG_HEX.replace(out, ID)
    out = OPAQUE.replace(out, REMOVED)
    kept.forEachIndexed { i, value -> out = out.replace("$KEEP_OPEN$i$KEEP_CLOSE", value) }
    return out
}
