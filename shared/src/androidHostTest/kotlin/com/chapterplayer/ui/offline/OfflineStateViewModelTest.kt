package com.chapterplayer.ui.offline

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.testing.FakeAppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineStateViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeMonitor(initial: Boolean = true) : ConnectivityMonitor {
        val online = MutableStateFlow(initial)
        override val isOnline: Flow<Boolean> = online
    }

    @Test
    fun `before anything is collected the state is online and not downloaded only`() {
        val vm = OfflineStateViewModel(FakeMonitor(false), FakeAppSettings())

        assertEquals(OfflineUiState(online = true, downloadedOnly = false), vm.uiState.value)
    }

    @Test
    fun `the state follows connectivity and the setting`() = runTest {
        val monitor = FakeMonitor(true)
        val settings = FakeAppSettings()
        val vm = OfflineStateViewModel(monitor, settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        monitor.online.value = false
        assertEquals(OfflineUiState(online = false, downloadedOnly = false), vm.uiState.value)

        settings.downloadedOnlyFlow.value = true
        assertEquals(OfflineUiState(online = false, downloadedOnly = true), vm.uiState.value)
    }

    @Test
    fun `setDownloadedOnly writes the setting`() = runTest {
        val settings = FakeAppSettings()
        val vm = OfflineStateViewModel(FakeMonitor(), settings)

        vm.setDownloadedOnly(true)

        assertEquals(true, settings.downloadedOnlyFlow.value)
    }
}
