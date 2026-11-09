package com.chapterplayer.domain.widgets

import com.chapterplayer.data.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetLookTest {

    @Test
    fun aChosenThemeIsAlwaysThatCard() {
        for (sdk in listOf(26, 30, 31, 35)) for (night in listOf(true, false)) {
            assertEquals(WidgetCard.DARK, widgetCard(ThemeMode.DARK, sdk, night))
            assertEquals(WidgetCard.LIGHT, widgetCard(ThemeMode.LIGHT, sdk, night))
        }
    }

    @Test
    fun followSystemLetsTheLauncherPickFromAndroid12() {
        assertEquals(WidgetCard.SYSTEM, widgetCard(ThemeMode.SYSTEM, 31, systemNight = true))
        assertEquals(WidgetCard.SYSTEM, widgetCard(ThemeMode.SYSTEM, 34, systemNight = false))
    }

    @Test
    fun followSystemBeforeAndroid12BakesTheCardWithTheColours() {
        // Glance bakes the text colours with the app's night mode there, so the card must come from the same one.
        assertEquals(WidgetCard.DARK, widgetCard(ThemeMode.SYSTEM, 30, systemNight = true))
        assertEquals(WidgetCard.LIGHT, widgetCard(ThemeMode.SYSTEM, 26, systemNight = false))
    }

    @Test
    fun aNightChangeRedrawsOnlyWhereColoursAreBaked() {
        assertTrue(widgetsRedrawOnNightChange(30))
        assertFalse(widgetsRedrawOnNightChange(31))
    }
}
