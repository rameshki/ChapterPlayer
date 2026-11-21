package com.chapterplayer.domain.playback

/** The skip-back lengths a person can choose, in seconds (review R26). */
val SKIP_BACK_CHOICES_SEC = listOf(5, 10, 15, 30, 45, 60)

/** The skip-forward lengths a person can choose, in seconds. */
val SKIP_FORWARD_CHOICES_SEC = listOf(10, 15, 30, 45, 60, 90)

const val DEFAULT_SKIP_BACK_SEC = 15
const val DEFAULT_SKIP_FORWARD_SEC = 30

/** [seconds] when it is one of [choices], else [default]: a stored value from an older or damaged file never gets through. */
fun normalizedSkipSec(seconds: Int?, choices: List<Int>, default: Int): Int =
    if (seconds != null && seconds in choices) seconds else default

/** What the player's back button says to a screen reader, and the notification's button: "Back 15 seconds". */
fun skipBackLabel(seconds: Int): String = "Back $seconds seconds"

fun skipForwardLabel(seconds: Int): String = "Forward $seconds seconds"

/** The Settings row's summary line, e.g. "Back 15 s, forward 30 s". */
fun skipSummary(backSec: Int, forwardSec: Int): String = "Back $backSec s, forward $forwardSec s"

