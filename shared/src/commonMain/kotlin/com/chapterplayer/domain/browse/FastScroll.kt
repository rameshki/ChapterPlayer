package com.chapterplayer.domain.browse

import com.chapterplayer.domain.car.titleLetter

/** Where a letter of the A to Z scroller starts in the list: the position of its first item. */
data class LetterIndex(val letter: Char, val firstIndex: Int)

/** The letters on the scroller strip, top to bottom: '#' (digits, symbols, other scripts) then A to Z. */
val FAST_SCROLL_ALPHABET: List<Char> = listOf('#') + ('A'..'Z')

/** Shorter lists scroll fine without the scroller. */
const val FAST_SCROLL_MIN_ITEMS = 20

/**
 * The letters that occur in [keys] (the display keys of the list exactly as shown, already sorted),
 * in list order, each with the position of its first key. The letter ignores a leading "The ", "A " or
 * "An " and is '#' for anything that is not A to Z. The positions are taken from the list as given, so a
 * list that is not in alphabetical order still jumps to the right rows.
 */
fun fastScrollLetters(keys: List<String>): List<LetterIndex> {
    val seen = mutableSetOf<Char>()
    val result = mutableListOf<LetterIndex>()
    keys.forEachIndexed { index, key ->
        val letter = titleLetter(key)
        if (seen.add(letter)) result += LetterIndex(letter, index)
    }
    return result
}

/** The scroller index for a list, or empty (no scroller) when [keys] is null or shorter than [FAST_SCROLL_MIN_ITEMS]. */
fun fastScrollIndex(keys: List<String>?): List<LetterIndex> =
    if (keys == null || keys.size < FAST_SCROLL_MIN_ITEMS) emptyList() else fastScrollLetters(keys)

/** The list position of [letter], or null when no item starts with it. */
fun indexForLetter(letters: List<LetterIndex>, letter: Char): Int? =
    letters.firstOrNull { it.letter == letter }?.firstIndex

/**
 * The letter under a finger at [fraction] of the strip's height (0 = top, 1 = bottom; clamped). The strip
 * holds every slot of [FAST_SCROLL_ALPHABET]; a missing letter is skipped: the finger keeps the present
 * letter above it, or the first present one below when nothing is above. Null when no letter is present.
 */
fun letterAtFraction(letters: List<LetterIndex>, fraction: Float): LetterIndex? {
    if (letters.isEmpty()) return null
    val slots = FAST_SCROLL_ALPHABET.size
    val clamped = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val slot = (clamped * slots).toInt().coerceIn(0, slots - 1)
    val byLetter = letters.associateBy { it.letter }
    for (i in slot downTo 0) byLetter[FAST_SCROLL_ALPHABET[i]]?.let { return it }
    for (i in slot + 1 until slots) byLetter[FAST_SCROLL_ALPHABET[i]]?.let { return it }
    return null
}
