package com.chapterplayer.ui.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelDeleteTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class ThrowingDeleteRepository : ServerRepository {
        var deleteCalls = 0
        override fun observeAll(): Flow<List<ServerConfig>> = emptyFlow()
        override suspend fun getById(id: String): ServerConfig? = null
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) { deleteCalls++; error("storage unavailable") }
    }

    @Test
    fun `a failed delete does not crash and the view model stays usable`() {
        val repo = ThrowingDeleteRepository()
        val viewModel = SettingsViewModel(repo)

        viewModel.deleteServer("a")
        viewModel.deleteServer("a")

        assertEquals(2, repo.deleteCalls)
    }
}
