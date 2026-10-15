package com.chapterplayer.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {

    private fun TestScope.newStore(file: File) =
        PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })

    private fun tempFile(): File = File.createTempFile("settings", ".preferences_pb").also { it.delete(); it.deleteOnExit() }

    @Test
    fun `a new install uses follow system, large covers and resume on car connect`() = runTest {
        val settings = DataStoreAppSettings(newStore(tempFile()))

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(LibraryLayout.LARGE_GRID, settings.libraryLayout.first())
        assertEquals(true, settings.resumeOnCarConnect.first())
    }

    @Test
    fun `changes are saved and read back`() = runTest {
        val settings = DataStoreAppSettings(newStore(tempFile()))

        settings.setThemeMode(ThemeMode.LIGHT)
        settings.setLibraryLayout(LibraryLayout.LIST)
        settings.setResumeOnCarConnect(false)

        assertEquals(ThemeMode.LIGHT, settings.themeMode.first())
        assertEquals(LibraryLayout.LIST, settings.libraryLayout.first())
        assertEquals(false, settings.resumeOnCarConnect.first())
    }

    @Test
    fun `an unknown stored value falls back to the default`() = runTest {
        val store = newStore(tempFile())
        store.edit { it[stringPreferencesKey("theme_mode")] = "PURPLE" }

        assertEquals(ThemeMode.SYSTEM, DataStoreAppSettings(store).themeMode.first())
    }

    @Test
    fun `a corrupted settings file falls back to the defaults`() = runTest {
        val file = tempFile().also { it.writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37, 0x42)) }

        val settings = DataStoreAppSettings(newStore(file))

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(true, settings.resumeOnCarConnect.first())
    }
}
