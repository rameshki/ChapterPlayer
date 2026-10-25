package com.chapterplayer.domain.browse

/*
 * The alphabetical keys shared by the sorts, the group lists, the A to Z scroller and the car's letter
 * ranges. Accented Latin letters count as their base letter ("Émile" is under E), so they sort and index
 * with it. Everything that does not start with A to Z after that (digits, symbols, other scripts) is the
 * '#' group, and the sort keys put that whole group first, so '#' is one block at the top of the list
 * just as it is at the top of the scroller.
 */

private const val ACCENTED = "àáâãäåāăąçćĉċčďđèéêëēĕėęěĝğġģĥħìíîïĩīĭįıĵķĺļľŀłñńņňòóôõöøōŏőŕŗřśŝşšţťŧùúûüũūŭůűųŵýÿŷźżž"
private const val PLAIN = "aaaaaaaaacccccddeeeeeeeeegggghhiiiiiiiiijklllllnnnnooooooooorrrsssstttuuuuuuuuuuwyyyzzz"
private val LIGATURES = mapOf('ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'þ' to "th", 'ð' to "d")

/** [s] in lower case with accented Latin letters replaced by their base letters. */
fun foldAccents(s: String): String {
    val lower = s.lowercase()
    val sb = StringBuilder(lower.length)
    for (c in lower) {
        val i = ACCENTED.indexOf(c)
        when {
            i >= 0 -> sb.append(PLAIN[i])
            c in LIGATURES -> sb.append(LIGATURES.getValue(c))
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private val ARTICLES = listOf("the ", "an ", "a ")

/** A title folded and trimmed, without a leading "The ", "A " or "An ". */
private fun titleCore(title: String): String {
    val t = foldAccents(title).trim()
    for (article in ARTICLES) if (t.startsWith(article)) return t.substring(article.length)
    return t
}

/** A name folded and trimmed; names keep a leading "The" or "A" ("The Great Courses" is under T). */
private fun personCore(name: String): String = foldAccents(name).trim()

private fun letterOf(core: String): Char {
    val c = core.firstOrNull() ?: return '#'
    return if (c in 'a'..'z') c.uppercaseChar() else '#'
}

// '#' entries get "0", lettered ones "1", so the '#' group sorts as one block before A.
private fun sortKeyOf(core: String): String = (if (letterOf(core) == '#') "0" else "1") + core

/** The letter a title is listed under: after a leading article, accents folded; '#' for anything else. */
fun titleLetterOf(title: String): Char = letterOf(titleCore(title))

/** The letter an author or narrator is listed under: the first letter of the name as written, accents folded. */
fun personLetter(name: String): Char = letterOf(personCore(name))

/** How titles compare (the Title sort, series names): agrees with [titleLetterOf]. */
internal fun titleSortKey(title: String): String = sortKeyOf(titleCore(title))

/** How author and narrator names compare (the Author sort, the Authors and Narrators lists): agrees with [personLetter]. */
internal fun personSortKey(name: String): String = sortKeyOf(personCore(name))
