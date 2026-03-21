package com.chapterplayer.navigation

/**
 * Sealed class defining all navigation destinations in the app.
 * Using a sealed class (rather than raw strings) gives us type safety
 * and a single source of truth for all routes.
 */
sealed class Screen(val route: String) {
    object Library : Screen("library")
    object Player : Screen("player/{bookId}") {
        fun createRoute(bookId: String) = "player/$bookId"
    }
    object Settings : Screen("settings")
    object ServerSetup : Screen("server_setup/{serverId}") {
        const val ARG_SERVER_ID = "serverId"
        const val NEW_SERVER = "new"
        fun createRoute(serverId: String = NEW_SERVER) = "server_setup/$serverId"
    }
}
