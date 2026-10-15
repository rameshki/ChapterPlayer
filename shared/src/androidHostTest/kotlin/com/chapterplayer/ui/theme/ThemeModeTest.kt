package com.chapterplayer.ui.theme

import com.chapterplayer.data.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModeTest {
    @Test
    fun `dark and light ignore the system setting`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemIsDark = false))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemIsDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemIsDark = false))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemIsDark = true))
    }

    @Test
    fun `follow system uses the system setting`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemIsDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemIsDark = false))
    }
}
