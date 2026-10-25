package com.chapterplayer.ui.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeDownloadStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.chapterplayer.domain.downloads.DownloadVolume
import com.chapterplayer.domain.downloads.GIGABYTE
import com.chapterplayer.domain.downloads.INTERNAL_VOLUME_ID

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private class FakeServers : ServerRepository {
        override fun observeAll(): Flow<List<ServerConfig>> = flowOf(emptyList())
        override suspend fun getById(id: String): ServerConfig? = null
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    /** Stands in for the app scope; runs at once. */
    private val appScope = CoroutineScope(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `the theme starts as follow system`() = runTest {
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings(), FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }

    @Test
    fun `choosing a theme saves it and updates the screen`() = runTest {
        val settings = FakeAppSettings()
        val vm = SettingsViewModel(FakeServers(), settings, FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())

        vm.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, settings.theme.value)
        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
    }

    @Test
    fun `the saved theme is shown when the screen opens`() = runTest {
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings(initialTheme = ThemeMode.DARK), FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())
        assertEquals(ThemeMode.DARK, vm.themeMode.value)
    }

    @Test
    fun `downloaded only can be switched on and off from settings`() = runTest {
        val settings = FakeAppSettings()
        val vm = SettingsViewModel(FakeServers(), settings, FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())
        assertEquals(false, vm.downloadedOnly.value)

        vm.setDownloadedOnly(true)
        assertEquals(true, settings.downloadedOnlyFlow.value)
        assertEquals(true, vm.downloadedOnly.value)

        vm.setDownloadedOnly(false)
        assertEquals(false, settings.downloadedOnlyFlow.value)
        assertEquals(false, vm.downloadedOnly.value)
    }

    @Test
    fun `the several-servers choice shows the saved one and saves a new one`() = runTest {
        val settings = FakeAppSettings()
        val vm = SettingsViewModel(FakeServers(), settings, FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())
        assertEquals(com.chapterplayer.domain.browse.SourcePreference.PREFER_DOWNLOADED, vm.sourcePreference.value)

        vm.setSourcePreference(com.chapterplayer.domain.browse.SourcePreference.PREFER_PLEX)
        assertEquals(com.chapterplayer.domain.browse.SourcePreference.PREFER_PLEX, settings.sourcePreferenceFlow.value)
        assertEquals(com.chapterplayer.domain.browse.SourcePreference.PREFER_PLEX, vm.sourcePreference.value)
    }

    @Test
    fun `resume when connected to the car starts on and can be switched off and on`() = runTest {
        val settings = FakeAppSettings()
        val vm = SettingsViewModel(FakeServers(), settings, FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())
        assertEquals(true, vm.resumeOnCarConnect.value)

        vm.setResumeOnCarConnect(false)
        assertEquals(false, settings.carResume.value)
        assertEquals(false, vm.resumeOnCarConnect.value)

        vm.setResumeOnCarConnect(true)
        assertEquals(true, settings.carResume.value)
        assertEquals(true, vm.resumeOnCarConnect.value)
    }

    @Test
    fun `wi-fi only starts on, and a change is saved then applied to the waiting downloads`() = runTest {
        val settings = FakeAppSettings()
        val downloads = FakeDownloadController()
        val vm = SettingsViewModel(FakeServers(), settings, downloads, FakeDownloadStorage(), appScope, FakeDownloadOverview())
        assertEquals(true, vm.downloadWifiOnly.value)

        vm.setDownloadWifiOnly(false)

        assertEquals(false, settings.wifiOnly.value)
        assertEquals(false, vm.downloadWifiOnly.value)
        assertEquals(listOf("reapply"), downloads.calls)
    }

    @Test
    fun `the wi-fi change runs in the app scope, not the screen's`() = runTest {
        val settings = FakeAppSettings()
        val downloads = FakeDownloadController()
        val app = TestScope(StandardTestDispatcher(testScheduler))
        val vm = SettingsViewModel(FakeServers(), settings, downloads, FakeDownloadStorage(), app, FakeDownloadOverview())

        vm.setDownloadWifiOnly(false)
        assertEquals(emptyList(), downloads.calls)

        app.testScheduler.advanceUntilIdle()
        assertEquals(false, settings.wifiOnly.value)
        assertEquals(listOf("reapply"), downloads.calls)
    }

    @Test
    fun `a wi-fi setting that could not be saved is not applied`() = runTest {
        val downloads = FakeDownloadController()
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings(failOnSet = true), downloads, FakeDownloadStorage(), appScope, FakeDownloadOverview())

        vm.setDownloadWifiOnly(false)

        assertEquals(true, vm.downloadWifiOnly.value)
        assertEquals(emptyList(), downloads.calls)
    }

    @Test
    fun `the storage limit starts at no limit and a choice is saved`() = runTest {
        val settings = FakeAppSettings()
        val vm = SettingsViewModel(FakeServers(), settings, FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())
        assertEquals(null, vm.downloadStorageLimit.value)

        vm.setDownloadStorageLimit(5 * GIGABYTE)
        assertEquals(5 * GIGABYTE, settings.storageLimit.value)
        assertEquals(5 * GIGABYTE, vm.downloadStorageLimit.value)

        vm.setDownloadStorageLimit(null)
        assertEquals(null, settings.storageLimit.value)
    }

    @Test
    fun `the download locations list each volume with its free space and the chosen one`() = runTest {
        val settings = FakeAppSettings()
        val storage = FakeDownloadStorage(
            volumes = listOf(
                DownloadVolume(INTERNAL_VOLUME_ID, "Internal storage", 30 * GIGABYTE),
                DownloadVolume("external:/sd", "SD card", 100 * GIGABYTE),
            )
        )
        val vm = SettingsViewModel(FakeServers(), settings, FakeDownloadController(), storage, appScope, FakeDownloadOverview())

        val shown = vm.downloadLocations.value
        assertEquals(listOf("Internal storage", "SD card"), shown.options.map { it.label })
        assertEquals(listOf("30 GB free", "100 GB free"), shown.options.map { it.detail })
        assertEquals(INTERNAL_VOLUME_ID, shown.selectedId)

        vm.setDownloadLocation("external:/sd")
        assertEquals("external:/sd", settings.locationId.value)
        assertEquals("external:/sd", vm.downloadLocations.value.selectedId)

        vm.setDownloadLocation(INTERNAL_VOLUME_ID)
        assertEquals(null, settings.locationId.value)
    }

    @Test
    fun `a chosen card that is gone shows internal storage as chosen`() = runTest {
        val settings = FakeAppSettings()
        settings.locationId.value = "external:/gone"
        val vm = SettingsViewModel(FakeServers(), settings, FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())
        assertEquals(INTERNAL_VOLUME_ID, vm.downloadLocations.value.selectedId)
    }

    @Test
    fun `the locations are read again when the screen asks, so a card put in shows up`() = runTest {
        val storage = FakeDownloadStorage()
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings(), FakeDownloadController(), storage, appScope, FakeDownloadOverview())
        assertEquals(1, vm.downloadLocations.value.options.size)

        storage.volumes = storage.volumes + DownloadVolume("external:/sd", "SD card", GIGABYTE)
        vm.refreshDownloadLocations()

        assertEquals(2, vm.downloadLocations.value.options.size)
    }

    @Test
    fun `a failed save does not crash and the view model stays usable`() = runTest {
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings(failOnSet = true), FakeDownloadController(), FakeDownloadStorage(), appScope, FakeDownloadOverview())

        vm.setThemeMode(ThemeMode.LIGHT)
        vm.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }

    @Test
    fun `the storage used by downloads follows the download overview`() = runTest {
        val overview = FakeDownloadOverview()
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings(), FakeDownloadController(), FakeDownloadStorage(), appScope, overview)
        assertEquals(0L, vm.downloadStorageUsed.value)

        overview.used.value = 1_200_000_000L

        assertEquals(1_200_000_000L, vm.downloadStorageUsed.value)
    }
}
