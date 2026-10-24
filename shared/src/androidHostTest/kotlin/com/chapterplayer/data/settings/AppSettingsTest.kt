package com.chapterplayer.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import com.chapterplayer.testing.InMemoryPreferencesDataStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {

    private fun TestScope.newStore(file: File) =
        PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })

    private fun tempFile(): File = File.createTempFile("settings", ".preferences_pb").also { it.delete(); it.deleteOnExit() }

    @Test
    fun `a new install uses follow system, large covers and resume on car connect`() = runTest {
        val settings = DataStoreAppSettings(InMemoryPreferencesDataStore())

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(LibraryLayout.LARGE_GRID, settings.libraryLayout.first())
        assertEquals(true, settings.resumeOnCarConnect.first())
    }

    @Test
    fun `changes are saved and read back`() = runTest {
        val settings = DataStoreAppSettings(InMemoryPreferencesDataStore())

        settings.setThemeMode(ThemeMode.LIGHT)
        settings.setLibraryLayout(LibraryLayout.LIST)
        settings.setResumeOnCarConnect(false)

        assertEquals(ThemeMode.LIGHT, settings.themeMode.first())
        assertEquals(LibraryLayout.LIST, settings.libraryLayout.first())
        assertEquals(false, settings.resumeOnCarConnect.first())
    }

    @Test
    fun `downloads have no storage limit and use internal storage until changed`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val settings = DataStoreAppSettings(store)
        assertEquals(null, settings.downloadStorageLimitBytes.first())
        assertEquals(null, settings.downloadLocationId.first())

        settings.setDownloadStorageLimitBytes(5_000_000_000)
        settings.setDownloadLocationId("external:/sd/Android/data/x/files")
        assertEquals(5_000_000_000, DataStoreAppSettings(store).downloadStorageLimitBytes.first())
        assertEquals("external:/sd/Android/data/x/files", DataStoreAppSettings(store).downloadLocationId.first())

        settings.setDownloadStorageLimitBytes(null)
        settings.setDownloadLocationId(null)
        assertEquals(null, settings.downloadStorageLimitBytes.first())
        assertEquals(null, settings.downloadLocationId.first())
    }

    @Test
    fun `a storage limit of zero or less reads as no limit`() = runTest {
        val settings = DataStoreAppSettings(InMemoryPreferencesDataStore())
        settings.setDownloadStorageLimitBytes(0)
        assertEquals(null, settings.downloadStorageLimitBytes.first())
    }

    @Test
    fun `downloaded only is off by default, can be turned on and off, and leaves other settings alone`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val settings = DataStoreAppSettings(store)
        assertEquals(false, settings.downloadedOnly.first())

        settings.setDownloadedOnly(true)
        settings.setThemeMode(ThemeMode.DARK)
        assertEquals(true, settings.downloadedOnly.first())
        assertEquals(true, DataStoreAppSettings(store).downloadedOnly.first())
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        assertEquals(true, settings.resumeOnCarConnect.first())

        settings.setDownloadedOnly(false)
        assertEquals(false, settings.downloadedOnly.first())
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
    }

    @Test
    fun `downloads are wi-fi only by default, can be switched, and leave other settings alone`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val settings = DataStoreAppSettings(store)
        assertEquals(true, settings.downloadWifiOnly.first())

        settings.setDownloadWifiOnly(false)
        settings.setThemeMode(ThemeMode.DARK)
        assertEquals(false, DataStoreAppSettings(store).downloadWifiOnly.first())
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        assertEquals(false, settings.downloadedOnly.first())

        settings.setDownloadWifiOnly(true)
        assertEquals(true, settings.downloadWifiOnly.first())
    }

    @Test
    fun `an unknown stored value falls back to the default`() = runTest {
        val store = InMemoryPreferencesDataStore()
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

    @Test
    fun `playback speed defaults to normal, is saved and read back, and leaves other settings alone`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val settings = DataStoreAppSettings(store)
        assertEquals(1.0f, settings.playbackSpeed.first())

        settings.setThemeMode(ThemeMode.DARK)
        settings.setPlaybackSpeed(1.25f)

        assertEquals(1.25f, settings.playbackSpeed.first())
        assertEquals(1.25f, DataStoreAppSettings(store).playbackSpeed.first())
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
    }

    @Test
    fun `a speed outside the supported range is clamped, and one between presets is kept`() = runTest {
        val settings = DataStoreAppSettings(InMemoryPreferencesDataStore())

        settings.setPlaybackSpeed(0.1f)
        assertEquals(0.5f, settings.playbackSpeed.first())
        settings.setPlaybackSpeed(9f)
        assertEquals(3.0f, settings.playbackSpeed.first())
        settings.setPlaybackSpeed(1.1f)
        assertEquals(1.1f, settings.playbackSpeed.first())
        settings.setPlaybackSpeed(Float.NaN)
        assertEquals(1.1f, settings.playbackSpeed.first())
    }
}
