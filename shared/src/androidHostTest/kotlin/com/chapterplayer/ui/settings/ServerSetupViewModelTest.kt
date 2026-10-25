package com.chapterplayer.ui.settings

import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.sync.DataStoreServerStatusStore
import com.chapterplayer.data.sync.PlexConnectionManager
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionKind
import com.chapterplayer.domain.plex.ProblemKind
import com.chapterplayer.testing.InMemoryPreferencesDataStore
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

    private class SavingRepository : ServerRepository {
        val saved = mutableListOf<ServerConfig>()
        override fun observeAll(): Flow<List<ServerConfig>> = emptyFlow()
        override suspend fun getById(id: String): ServerConfig? = saved.lastOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) { saved += config }
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    @Test
    fun `picking a Plex server tries its connections and saves the one that answered with the list`() {
        val repo = SavingRepository()
        val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())
        val local = PlexCandidate("https://192-0-2-11.abc.plex.direct:32400", PlexConnectionKind.LOCAL)
        val raw = PlexCandidate("http://192.0.2.11:32400", PlexConnectionKind.LOCAL_ADDRESS)
        val probed = mutableListOf<Pair<String, String>>()
        val viewModel = ServerSetupViewModel(
            repo,
            PlexTvApiService(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { installApiDefaults(isDebuggable = false) }),
            object : PlexClientIdentifier { override val value = "client-1" },
            object : AbsSignInPort {
                override suspend fun withPassword(address: String, username: String, password: String) = error("unused")
                override suspend fun withApiKey(address: String, apiKey: String) = error("unused")
            },
            plexConnections = PlexConnectionManager(repo, store, { _, _ -> null }, { null }, nowMs = { 5L }),
            plexProbe = { uri, token ->
                probed += uri to token
                if (uri == local.uri) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null
            }
        )
        viewModel.onSourceChanged(ServerSource.PLEX)

        viewModel.onPlexServerSelected(PlexServerOption("Home", local.uri, "server-token", listOf(local, raw)))

        val state = viewModel.uiState.value
        assertFalse(state.plexCheckingConnection)
        assertEquals(raw.uri, state.baseUrl)
        assertEquals(raw, state.plexChosen)
        assertEquals("This network blocks the plex.direct name; using the local address", state.plexConnectionNote)
        assertEquals(setOf("server-token"), probed.map { it.second }.toSet())

        viewModel.saveServer(null)

        val saved = repo.saved.single()
        assertEquals(raw.uri, saved.baseUrl)
        val record = kotlinx.coroutines.runBlocking { store.get(saved.id) }
        assertEquals(listOf(local, raw), record.candidates.map { it.toCandidate() })
        assertEquals(raw.uri, record.chosenUri)
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
