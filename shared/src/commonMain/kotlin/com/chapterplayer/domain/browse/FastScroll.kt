package com.chapterplayer.domain.browse

/** Where a letter of the A to Z scroller starts in the list: the position of its first item. */
data class LetterIndex(val letter: Char, val firstIndex: Int)

/** The letters on the scroller strip, top to bottom: '#' (digits, symbols, other scripts) then A to Z. */
val FAST_SCROLL_ALPHABET: List<Char> = listOf('#') + ('A'..'Z')

/** How the keys of a list are lettered: titles skip a leading article, names do not. */
enum class LetterRule { TITLE, PERSON }

/** The keys of a list as shown and the rule that letters them. */
data class FastScrollKeys(val keys: List<String>, val rule: LetterRule)

/** Shorter lists scroll fine without the scroller. */
const val FAST_SCROLL_MIN_ITEMS = 20

/**
 * The letters that occur in [keys] (the display keys of the list exactly as shown, already sorted),
 * in list order, each with the position of its first key. [rule] picks the letter: a title ignores a
 * leading "The ", "A " or "An " ([titleLetterOf]), a name does not ([personLetter]); both fold accents
 * and give '#' for anything that is not A to Z. The positions are taken from the list as given, so a
 * list that is not in alphabetical order still jumps to the right rows.
 */
fun fastScrollLetters(keys: List<String>, rule: LetterRule = LetterRule.TITLE): List<LetterIndex> {
    val seen = mutableSetOf<Char>()
    val result = mutableListOf<LetterIndex>()
    keys.forEachIndexed { index, key ->
        val letter = if (rule == LetterRule.TITLE) titleLetterOf(key) else personLetter(key)
        if (seen.add(letter)) result += LetterIndex(letter, index)
    }
    return result
}

/** The scroller index for a list, or empty (no scroller) when [keys] is null or shorter than [FAST_SCROLL_MIN_ITEMS]. */
fun fastScrollIndex(keys: FastScrollKeys?): List<LetterIndex> =
    if (keys == null || keys.keys.size < FAST_SCROLL_MIN_ITEMS) emptyList() else fastScrollLetters(keys.keys, keys.rule)

/** The list position of [letter], or null when no item starts with it. */
fun indexForLetter(letters: List<LetterIndex>, letter: Char): Int? =
    letters.firstOrNull { it.letter == letter }?.firstIndex

/**
 * The letter under a finger at [fraction] of the strip's height (0 = top, 1 = bottom; clamped). The strip
 * holds every slot of [FAST_SCROLL_ALPHABET]; a missing letter is skipped: the finger keeps the present
 * letter above it, or the first present one below when nothing is above. Null when no letter is present.
 */
fun letterAtFraction(letters: List<LetterIndex>, fraction: Float): LetterIndex? =
    letterAtFraction(letters.associateBy { it.letter }, fraction)

/** [letterAtFraction] over an index already keyed by letter, so a drag does not rebuild the map on every move. */
fun letterAtFraction(byLetter: Map<Char, LetterIndex>, fraction: Float): LetterIndex? {
    if (byLetter.isEmpty()) return null
    val slots = FAST_SCROLL_ALPHABET.size
    val clamped = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val slot = (clamped * slots).toInt().coerceIn(0, slots - 1)
    for (i in slot downTo 0) byLetter[FAST_SCROLL_ALPHABET[i]]?.let { return it }
    for (i in slot + 1 until slots) byLetter[FAST_SCROLL_ALPHABET[i]]?.let { return it }
    return null
}
