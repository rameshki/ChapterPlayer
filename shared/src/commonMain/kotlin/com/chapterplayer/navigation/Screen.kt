package com.chapterplayer.navigation

import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.library.encodeGroupKey

/**
 * Sealed class defining all navigation destinations in the app.
 * Using a sealed class (rather than raw strings) gives us type safety
 * and a single source of truth for all routes.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Library : Screen("library")
    object Downloads : Screen("downloads")
    object Search : Screen("search")
    object Player : Screen("player/{bookId}") {
        fun createRoute(bookId: String) = "player/$bookId"
    }
    object GroupDetail : Screen("group/{kind}/{key}") {
        const val ARG_KIND = "kind"
        const val ARG_KEY = "key"

        /** [key] travels hex-encoded, because names hold characters that routes decode differently. */
        fun createRoute(kind: LibraryView, key: String) = "group/${kind.name}/${encodeGroupKey(key)}"
    }
    object Settings : Screen("settings")
    object ServerSetup : Screen("server_setup/{serverId}") {
        const val ARG_SERVER_ID = "serverId"
        const val NEW_SERVER = "new"
        fun createRoute(serverId: String = NEW_SERVER) = "server_setup/$serverId"
    }
}
