package com.chapterplayer.ui.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.testing.FakeAppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private class FakeServers : ServerRepository {
        override fun observeAll(): Flow<List<ServerConfig>> = flowOf(emptyList())
        override suspend fun getById(id: String): ServerConfig? = null
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
    }

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `the theme starts as follow system`() = runTest {
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings())
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }

    @Test
    fun `choosing a theme saves it and updates the screen`() = runTest {
        val settings = FakeAppSettings()
        val vm = SettingsViewModel(FakeServers(), settings)

        vm.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, settings.theme.value)
        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
    }

    @Test
    fun `the saved theme is shown when the screen opens`() = runTest {
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings(initialTheme = ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, vm.themeMode.value)
    }

    @Test
    fun `a failed save does not crash and the view model stays usable`() = runTest {
        val vm = SettingsViewModel(FakeServers(), FakeAppSettings(failOnSet = true))

        vm.setThemeMode(ThemeMode.LIGHT)
        vm.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }
}
