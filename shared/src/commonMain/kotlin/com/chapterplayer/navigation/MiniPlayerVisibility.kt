package com.chapterplayer.navigation

/**
 * Whether the mini player shows: a book is loaded, the destination is known, and it is neither the
 * full player nor the Settings and Add server screens. [route] is the destination's route pattern.
 */
fun miniPlayerVisible(route: String?, hasBook: Boolean): Boolean {
    if (!hasBook || route == null) return false
    val base = route.substringBefore('/')
    return base != Screen.Player.route && base != Screen.Settings.route && base != SERVER_SETUP_BASE
}

private val SERVER_SETUP_BASE = Screen.ServerSetup.route.substringBefore('/')
