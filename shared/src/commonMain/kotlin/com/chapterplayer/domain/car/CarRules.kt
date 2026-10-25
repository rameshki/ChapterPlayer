package com.chapterplayer.domain.car

import com.chapterplayer.domain.browse.titleLetterOf

/** A browse node of the car's tree: [kind] (continue, series, ...) and an optional [arg] (a group key or id). */
data class CarNode(val kind: String, val arg: String?)

/** How far a controller that connects to the playback service is trusted. */
enum class CarTrust { OWN, CAR, NONE }

/** A group of letters in the All books list ("A to C"), from [first] to [last]; '#' stands for non-letters. */
data class LetterRange(val label: String, val first: Char, val last: Char)

/** What voice search looks at for one book. */
data class BookSearchEntry(val id: String, val title: String, val author: String?, val series: String?)

/** Packages that may browse and start books: Android Auto, the Assistant and the system. */
val TRUSTED_CAR_PACKAGES: Set<String> = setOf(
    "com.google.android.projection.gearhead",
    "com.google.android.googlequicksearchbox",
    "com.google.android.carassistant",
    "com.android.systemui",
    "com.google.android.autosimulator",
)

private const val NODE_PREFIX = "car:"
private const val MAX_RANGE_TITLES = 25

/** The media id of a browse node: `car:<kind>` or `car:<kind>:<arg>`. */
fun carNodeId(kind: String, arg: String? = null): String =
    if (arg == null) "$NODE_PREFIX$kind" else "$NODE_PREFIX$kind:$arg"

/** Reads a browse node id back; null for a book id or a broken id. The argument may contain colons. */
fun parseCarNodeId(id: String): CarNode? {
    if (!id.startsWith(NODE_PREFIX)) return null
    val rest = id.substring(NODE_PREFIX.length)
    if (rest.isEmpty()) return null
    val colon = rest.indexOf(':')
    if (colon < 0) return CarNode(rest, null)
    if (colon == 0) return null
    return CarNode(rest.substring(0, colon), rest.substring(colon + 1))
}

/** The caller's package decides: this app, a known car package, or nobody we trust. */
fun isTrustedCarController(callerPackage: String?, ownPackage: String): CarTrust = when {
    callerPackage == null -> CarTrust.NONE
    callerPackage == ownPackage -> CarTrust.OWN
    callerPackage in TRUSTED_CAR_PACKAGES -> CarTrust.CAR
    else -> CarTrust.NONE
}

/**
 * The letter a title is listed under: the first letter after a leading "The ", "A " or "An ", accents
 * folded ("Émile" is under E); '#' for anything else. The same rule as the Title sort ([titleLetterOf]).
 */
fun titleLetter(title: String): Char = titleLetterOf(title)

/** Groups the letters that occur into ranges of at most 25 titles; a letter with more titles stands alone. */
fun letterRanges(titles: List<String>): List<LetterRange> {
    val counts = mutableMapOf<Char, Int>()
    for (title in titles) {
        val letter = titleLetter(title)
        counts[letter] = (counts[letter] ?: 0) + 1
    }
    val ranges = mutableListOf<LetterRange>()
    var first: Char? = null
    var last = ' '
    var total = 0
    fun close() {
        val f = first ?: return
        ranges += LetterRange(if (f == last) "$f" else "$f to $last", f, last)
        first = null
        total = 0
    }
    for ((letter, n) in counts.entries.sortedBy { rank(it.key) }.map { it.key to it.value }) {
        if (first != null && total + n > MAX_RANGE_TITLES) close()
        if (first == null) first = letter
        last = letter
        total += n
    }
    close()
    return ranges
}

/** Whether [title] is listed in [range] ('#' sorts before 'A'). */
fun titleInRange(title: String, range: LetterRange): Boolean {
    val letter = titleLetter(title)
    return rank(letter) >= rank(range.first) && rank(letter) <= rank(range.last)
}

private fun rank(c: Char): Char = if (c == '#') ' ' else c

internal fun normalize(s: String): String {
    val sb = StringBuilder()
    for (c in s.lowercase()) sb.append(if (c.isLetterOrDigit()) c else ' ')
    return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * The ids of books that match a spoken query, best first: an exact title, then a title that starts with the
 * query, then books whose title, author or series contain every word. An empty query matches nothing.
 */
fun matchBooksForQuery(query: String, books: List<BookSearchEntry>): List<String> {
    val nq = normalize(query)
    if (nq.isEmpty()) return emptyList()
    val words = nq.split(' ')
    val tiered = mutableListOf<Pair<Int, String>>()
    for (book in books) {
        val nt = normalize(book.title)
        val haystack = normalize(book.title + " " + (book.author ?: "") + " " + (book.series ?: ""))
        val tier = when {
            nt == nq -> 0
            nt.startsWith(nq) -> 1
            words.all { it in haystack } -> 2
            else -> continue
        }
        tiered += tier to book.id
    }
    return tiered.sortedBy { it.first }.map { it.second }
}

/** The second line of a car list row: author, how far along, and whether it needs a connection. */
fun carSubtitle(author: String?, progressPercent: Int?, offline: Boolean): String {
    val parts = mutableListOf<String>()
    author?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    if (progressPercent != null) {
        if (progressPercent >= 100) parts += "Finished" else if (progressPercent >= 1) parts += "$progressPercent% listened"
    }
    if (offline) parts += "Needs a connection"
    return parts.joinToString(" · ")
}
