package com.chapterplayer.navigation

import com.chapterplayer.data.PlatformFeatures
import kotlin.test.Test
import kotlin.test.assertEquals

class VisibleTabsTest {

    @Test
    fun `Android shows all four tabs in bar order`() {
        assertEquals(listOf("Home", "Library", "Downloads", "Search"), visibleTabs(PlatformFeatures.Full).map { it.label })
    }

    @Test
    fun `without downloads there is no Downloads tab`() {
        val tabs = visibleTabs(PlatformFeatures.IosBrowsingOnly)
        assertEquals(listOf("Home", "Library", "Search"), tabs.map { it.label })
        assertEquals(listOf(Screen.Home, Screen.Library, Screen.Search), tabs.map { it.screen })
    }
}
