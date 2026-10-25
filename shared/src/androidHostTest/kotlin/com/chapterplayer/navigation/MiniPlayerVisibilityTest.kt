package com.chapterplayer.navigation

import com.chapterplayer.ui.library.LibraryView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiniPlayerVisibilityTest {

    @Test
    fun `the player route has no argument`() {
        assertEquals("player", Screen.Player.route)
    }

    @Test
    fun `shows on the tabs, details and group pages while a book is loaded`() {
        listOf(
            Screen.Home.route, Screen.Library.route, Screen.Downloads.route, Screen.Search.route,
            Screen.BookDetails.route, Screen.GroupDetail.route,
            Screen.BookDetails.createRoute("li_1"),
            Screen.GroupDetail.createRoute(LibraryView.entries.first { it.isGrouped }, "Name")
        ).forEach { assertTrue(miniPlayerVisible(it, true), it) }
    }

    @Test
    fun `hides on the player, settings and add server`() {
        listOf(
            Screen.Player.route, Screen.Settings.route, Screen.ServerSetup.route,
            Screen.ServerSetup.createRoute(), Screen.ServerSetup.createRoute("srv-1"),
            Screen.SettingsServers.route, Screen.SettingsAppearance.route, Screen.SettingsDownloads.route,
            Screen.SettingsCar.route, Screen.SettingsAbout.route
        ).forEach { assertFalse(miniPlayerVisible(it, true), it) }
    }

    @Test
    fun `hides without a book on every route`() {
        listOf(Screen.Home.route, Screen.BookDetails.route, Screen.Player.route, Screen.Settings.route, null)
            .forEach { assertFalse(miniPlayerVisible(it, false), it.toString()) }
    }

    @Test
    fun `hides when the route is unknown`() {
        assertFalse(miniPlayerVisible(null, true))
    }
}
