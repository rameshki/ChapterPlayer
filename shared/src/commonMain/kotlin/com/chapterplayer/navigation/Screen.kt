package com.chapterplayer.navigation

import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.library.decodeGroupKey
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
    /** Always shows what is playing, so it takes no argument. */
    object Player : Screen("player")
    object BookDetails : Screen("book/{id}") {
        const val ARG_ID = "id"

        /** [bookId] travels hex-encoded like a group key: ids hold underscores and may hold more. */
        fun createRoute(bookId: String) = "book/${encodeGroupKey(bookId)}"

        /** True when the entry on top is this route showing [bookId] ([encodedId] is its raw argument). */
        fun isShowing(currentRoute: String?, encodedId: String?, bookId: String): Boolean =
            currentRoute == route && encodedId != null && decodeGroupKey(encodedId) == bookId
    }
    object GroupDetail : Screen("group/{kind}/{key}") {
        const val ARG_KIND = "kind"
        const val ARG_KEY = "key"

        /** [key] travels hex-encoded, because names hold characters that routes decode differently. */
        fun createRoute(kind: LibraryView, key: String) = "group/${kind.name}/${encodeGroupKey(key)}"
    }
    /** The Settings home; every page below is its own route, so Back returns here. */
    object Settings : Screen("settings")
    object SettingsServers : Screen("settings/servers")
    object SettingsAppearance : Screen("settings/appearance")
    object SettingsDownloads : Screen("settings/downloads")
    object SettingsCar : Screen("settings/car")
    object SettingsAbout : Screen("settings/about")
    object ServerSetup : Screen("server_setup/{serverId}") {
        const val ARG_SERVER_ID = "serverId"
        const val NEW_SERVER = "new"
        fun createRoute(serverId: String = NEW_SERVER) = "server_setup/$serverId"
    }
}
