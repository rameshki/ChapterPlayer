package com.chapterplayer.ui.settings

import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.repository.ServerRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSetupViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class ThrowingRepository : ServerRepository {
        override fun observeAll(): Flow<List<ServerConfig>> = emptyFlow()
        override suspend fun getById(id: String): ServerConfig? = null
        override suspend fun save(config: ServerConfig) = error("keystore unavailable")
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    @Test
    fun `a failed save shows an error instead of crashing`() {
        val plex = PlexTvApiService(
            HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { installApiDefaults(isDebuggable = false) }
        )
        val viewModel = ServerSetupViewModel(
            ThrowingRepository(), plex,
            object : PlexClientIdentifier { override val value = "client-1" },
            object : AbsSignInPort {
                override suspend fun withPassword(address: String, username: String, password: String) = error("unused")
                override suspend fun withApiKey(address: String, apiKey: String) = error("unused")
            }
        )
        viewModel.onNameChanged("Test")
        viewModel.onBaseUrlChanged("http://s.test")
        viewModel.onTokenChanged("tok")

        viewModel.saveServer(null)

        val state = viewModel.uiState.value
        assertFalse(state.isSaving)
        assertFalse(state.savedSuccessfully)
        assertNotNull(state.saveError)
    }
}
