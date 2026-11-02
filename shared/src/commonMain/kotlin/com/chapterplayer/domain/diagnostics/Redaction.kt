package com.chapterplayer.domain.diagnostics

/*
 * The diagnostics report is read by a person and by Claude over a log, and may be pasted anywhere, so one last pass
 * removes everything that must never leave the phone: tokens, passwords, auth headers, addresses (URLs, hosts, IPv4,
 * IPv6), machine ids and long opaque ids. The report builder also avoids writing these; this pass is the safety net
 * and is tested with realistic values.
 */

private const val REMOVED = "[removed]"
private const val ADDRESS = "[address]"
private const val ID = "[id]"

private val HEADER_LINE = Regex(
    """(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\s*[:=][^\r\n]*"""
)
private val BEARER = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")
private val URL = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>)\]]+""")
private val SECRET_PAIR = Regex(
    """(?i)\b(x-plex-token|token|access_token|refresh_token|id_token|api[_-]?key|apikey|password|passwd|pwd|secret|sig|signature)["']?\s*[=:]\s*("[^"]*"|'[^']*'|[^\s,;&"']+)"""
)
private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]*)?""")
private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
private val LONG_HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")
private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")
private val IPV6 = Regex("""(?<![\w:])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?![\w:])""")
private val HOST = Regex(
    """(?i)\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?!(?:mp3|m4a|m4b|mp4|flac|ogg|opus|aac|wav|wma|jpg|jpeg|png|part|json|txt)\b)[a-z]{2,}\b(?::\d{1,5})?"""
)
private val OPAQUE = Regex("""\b(?=[A-Za-z0-9]*\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{20,}\b""")

private const val KEEP_OPEN = ''
private const val KEEP_CLOSE = ''

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
    out = HOST.replace(out, ADDRESS)
    out = UUID.replace(out, ID)
    out = LONG_HEX.replace(out, ID)
    out = OPAQUE.replace(out, REMOVED)
    kept.forEachIndexed { i, value -> out = out.replace("$KEEP_OPEN$i$KEEP_CLOSE", value) }
    return out
}
