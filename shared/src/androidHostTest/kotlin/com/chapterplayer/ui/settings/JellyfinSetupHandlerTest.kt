package com.chapterplayer.ui.settings

import com.chapterplayer.data.auth.JellyfinSignInError
import com.chapterplayer.data.auth.JellyfinSignInException
import com.chapterplayer.data.auth.JellyfinSignInPort
import com.chapterplayer.data.auth.JellyfinSignInResult
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.sync.ServerStatusRecord
import com.chapterplayer.data.sync.ServerStatusStore
import com.chapterplayer.domain.jellyfin.JELLYFIN_ID_MISSING_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_ID_UNREADABLE_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_INVALID_ADDRESS_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_NOT_JELLYFIN_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_REDIRECT_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_TLS_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_NO_BOOKS_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_OTHER_SERVER_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_TOO_OLD_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_WRONG_PASSWORD_TEXT
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinSetupHandlerTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val token = "a6f98b2a08d92285b19af86a9503bf50"
    private val serverId = "36b7674f7149d16771400fae9b9eb1f9"

    private class Call(val address: String, val rowId: String, val username: String, val password: String, val expected: String?)

    private inner class FakePort(var outcome: (String) -> JellyfinSignInResult = { row -> result(row) }) : JellyfinSignInPort {
        val calls = mutableListOf<Call>()
        override suspend fun withPassword(
            address: String, serverRowId: String, username: String, password: String, expectedServerId: String?,
        ): JellyfinSignInResult {
            calls += Call(address, serverRowId, username, password, expectedServerId)
            return outcome(serverRowId)
        }
    }

    private fun result(row: String) = JellyfinSignInResult(
        token, serverId, "user-1", "12.4.0",
        listOf(LibraryInfo(row, "v1", "Audiobooks"), LibraryInfo(row, "v2", "Kids Books")),
    )

    private class FakeRepository : ServerRepository {
        val saved = mutableListOf<ServerConfig>()
        val userAddressChanges = mutableListOf<ServerConfig>()
        override suspend fun saveUserAddressChange(config: ServerConfig) {
            userAddressChanges += config
            save(config)
        }
        val stored = mutableMapOf<String, ServerConfig>()
        override fun observeAll(): Flow<List<ServerConfig>> = emptyFlow()
        override suspend fun getById(id: String): ServerConfig? = stored[id]
        override suspend fun save(config: ServerConfig) {
            saved += config
            stored[config.id] = config
        }
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    private class FakeStatusStore : ServerStatusStore {
        val records = mutableMapOf<String, ServerStatusRecord>()
        var failGet = false
        var failUpdate = false
        override val all: Flow<Map<String, ServerStatusRecord>> = MutableStateFlow(emptyMap())
        override suspend fun get(serverId: String): ServerStatusRecord {
            if (failGet) error("datastore unreadable")
            return records[serverId] ?: ServerStatusRecord()
        }
        override suspend fun update(serverId: String, transform: (ServerStatusRecord) -> ServerStatusRecord) {
            if (failUpdate) error("disk full")
            records[serverId] = transform(records[serverId] ?: ServerStatusRecord())
        }
        override suspend fun remove(serverId: String) { records.remove(serverId) }
    }

    private val repo = FakeRepository()
    private val status = FakeStatusStore()

    private fun viewModel(port: JellyfinSignInPort): ServerSetupViewModel {
        val plex = PlexTvApiService(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { installApiDefaults(isDebuggable = false) })
        return ServerSetupViewModel(
            repo, plex, object : PlexClientIdentifier { override val value = "client-1" },
            absSignIn = object : com.chapterplayer.data.auth.AbsSignInPort {
                override suspend fun withPassword(address: String, username: String, password: String) = error("not used")
                override suspend fun withApiKey(address: String, apiKey: String) = error("not used")
            },
            jellyfinSignIn = port,
            statusStore = status,
        )
    }

    private fun ServerSetupViewModel.startAdd() {
        onSourceChanged(ServerSource.JELLYFIN)
        onJellyfinAddressChanged("https://jf.test/jellyfin/")
        onJellyfinUsernameChanged("reader")
        onJellyfinPasswordChanged("pw-made-up")
    }

    @Test
    fun `the row id is made when the add flow starts and used for sign-in and the saved row`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.onSourceChanged(ServerSource.JELLYFIN)
        val rowId = vm.uiState.value.jellyfin.pendingRowId
        assertTrue(rowId.isNotBlank())

        vm.startAdd()
        assertEquals(rowId, vm.uiState.value.jellyfin.pendingRowId)
        vm.onJellyfinSignIn()
        vm.onJellyfinSave()

        assertEquals(rowId, port.calls.single().rowId)
        assertEquals(rowId, repo.saved.single().id)
        assertNull(port.calls.single().expected)
    }

    @Test
    fun `signing in lists every books view ticked and clears the password`() {
        val vm = viewModel(FakePort())
        vm.startAdd()

        vm.onJellyfinSignIn()

        val s = vm.uiState.value.jellyfin
        assertEquals(JellyfinStep.LIBRARIES, s.step)
        assertEquals(listOf(LibraryChoice("v1", "Audiobooks", true), LibraryChoice("v2", "Kids Books", true)), s.libraries)
        assertEquals("", s.password)
        assertNull(s.signInError)
        assertFalse(s.isSigningIn)
    }

    @Test
    fun `saving stores the token the server id and the turned off views`() {
        val vm = viewModel(FakePort())
        vm.onNameChanged("Den Jellyfin")
        vm.startAdd()
        vm.onJellyfinSignIn()
        vm.onJellyfinLibraryToggled("v2")

        vm.onJellyfinSave()

        val saved = repo.saved.single()
        assertEquals(ServerSource.JELLYFIN, saved.source)
        assertEquals("Den Jellyfin", saved.name)
        assertEquals("https://jf.test/jellyfin", saved.baseUrl)
        // The repository puts the token in the credential store and keeps the Room column empty (ServerRepositoryTest).
        assertEquals(token, saved.token)
        assertEquals(setOf("v2"), saved.excludedLibraryIds)
        assertNull(saved.libraryId)
        assertEquals(serverId, status.records.getValue(saved.id).jellyfinServerId)
        assertTrue(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun `a blank name is the address host`() {
        val vm = viewModel(FakePort())
        vm.startAdd()
        vm.onJellyfinSignIn()
        vm.onJellyfinSave()
        assertEquals("jf.test", repo.saved.single().name)
    }

    @Test
    fun `each sign-in error shows its words and stores nothing`() {
        val cases = listOf(
            JellyfinSignInError.Unreachable to "Couldn't reach the server. Check the address and your network.",
            JellyfinSignInError.NotJellyfin to JELLYFIN_NOT_JELLYFIN_TEXT,
            JellyfinSignInError.TooOld("10.9.11") to JELLYFIN_TOO_OLD_TEXT,
            JellyfinSignInError.WrongCredentials to JELLYFIN_WRONG_PASSWORD_TEXT,
            JellyfinSignInError.NoBooksLibrary to JELLYFIN_NO_BOOKS_TEXT,
            JellyfinSignInError.OtherServer to JELLYFIN_OTHER_SERVER_TEXT,
            JellyfinSignInError.ServerError(502) to "The server returned an error (code 502).",
            JellyfinSignInError.InvalidAddress to JELLYFIN_INVALID_ADDRESS_TEXT,
            JellyfinSignInError.Redirected to JELLYFIN_REDIRECT_TEXT,
            JellyfinSignInError.SecureConnection to JELLYFIN_TLS_TEXT,
        )
        for ((error, message) in cases) {
            val vm = viewModel(FakePort { throw JellyfinSignInException(error) })
            vm.startAdd()

            vm.onJellyfinSignIn()

            val s = vm.uiState.value.jellyfin
            assertEquals(message, s.signInError)
            assertEquals(JellyfinStep.SIGN_IN, s.step)
            assertFalse(s.isSigningIn)
        }
        assertTrue(repo.saved.isEmpty())
        assertTrue(status.records.isEmpty())
    }

    @Test
    fun `sign in again sends the stored server id and saves the new token`() {
        val stored = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", "")
        repo.stored[stored.id] = stored
        status.records[stored.id] = ServerStatusRecord(jellyfinServerId = serverId)
        val port = FakePort()
        val vm = viewModel(port)
        vm.loadServer(stored.id)
        vm.onJellyfinUsernameChanged("reader")
        vm.onJellyfinPasswordChanged("pw-made-up")

        vm.onJellyfinSignInAgain(stored.id)

        val call = port.calls.single()
        assertEquals("row-9", call.rowId)
        assertEquals(serverId, call.expected)
        assertEquals("https://jf.test/jellyfin", call.address)
        assertEquals(token, repo.saved.single().token)
        assertEquals("row-9", repo.saved.single().id)
        assertEquals("", vm.uiState.value.jellyfin.password)
        assertTrue(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun `sign in again on another server stores nothing`() {
        val stored = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", "")
        repo.stored[stored.id] = stored
        status.records[stored.id] = ServerStatusRecord(jellyfinServerId = serverId)
        val vm = viewModel(FakePort { throw JellyfinSignInException(JellyfinSignInError.OtherServer) })
        vm.loadServer(stored.id)
        vm.onJellyfinPasswordChanged("pw-made-up")

        vm.onJellyfinSignInAgain(stored.id)

        assertEquals(JELLYFIN_OTHER_SERVER_TEXT, vm.uiState.value.jellyfin.signInError)
        assertTrue(repo.saved.isEmpty())
        assertEquals(serverId, status.records.getValue(stored.id).jellyfinServerId)
        assertFalse(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun `sign in again without a stored server id is refused`() {
        repo.stored["row-9"] = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", "")
        val port = FakePort()
        val vm = viewModel(port)
        vm.loadServer("row-9")
        vm.onJellyfinPasswordChanged("pw-made-up")

        vm.onJellyfinSignInAgain("row-9")

        assertTrue(port.calls.isEmpty())
        assertEquals(JELLYFIN_ID_MISSING_TEXT, vm.uiState.value.jellyfin.signInError)
        assertTrue(repo.saved.isEmpty())
        assertFalse(vm.uiState.value.jellyfin.isSigningIn)
    }

    @Test
    fun `sign in again is refused when the stored server id cannot be read`() {
        repo.stored["row-9"] = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", "")
        status.records["row-9"] = ServerStatusRecord(jellyfinServerId = serverId)
        status.failGet = true
        val port = FakePort()
        val vm = viewModel(port)
        vm.loadServer("row-9")

        vm.onJellyfinSignInAgain("row-9")

        assertTrue(port.calls.isEmpty())
        assertEquals(JELLYFIN_ID_UNREADABLE_TEXT, vm.uiState.value.jellyfin.signInError)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `an address edit without a stored server id is refused`() {
        repo.stored["row-9"] = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", token)
        val vm = viewModel(FakePort())
        vm.loadServer("row-9")
        vm.onBaseUrlChanged("https://other.test/jellyfin")

        vm.saveServer("row-9")

        assertTrue(repo.saved.isEmpty())
        assertEquals(JELLYFIN_ID_MISSING_TEXT, vm.uiState.value.saveError)
    }

    @Test
    fun `an address edit is refused when the stored server id cannot be read`() {
        repo.stored["row-9"] = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", token)
        status.records["row-9"] = ServerStatusRecord(jellyfinServerId = serverId)
        status.failGet = true
        val vm = viewModel(FakePort())
        vm.loadServer("row-9")
        vm.onBaseUrlChanged("https://other.test/jellyfin")

        vm.saveServer("row-9")

        assertTrue(repo.saved.isEmpty())
        assertEquals(JELLYFIN_ID_UNREADABLE_TEXT, vm.uiState.value.saveError)
    }

    @Test
    fun `adding fails loudly when the server id cannot be kept`() {
        status.failUpdate = true
        val vm = viewModel(FakePort())
        vm.startAdd()
        vm.onJellyfinSignIn()

        vm.onJellyfinSave()

        assertTrue(repo.saved.isEmpty())
        assertEquals(SAVE_FAILED_MESSAGE, vm.uiState.value.jellyfin.saveError)
        assertFalse(vm.uiState.value.savedSuccessfully)
        assertFalse(vm.uiState.value.jellyfin.isSaving)
    }

    @Test
    fun `the server id is kept before the row is saved`() {
        val vm = viewModel(FakePort())
        vm.startAdd()
        vm.onJellyfinSignIn()
        val rowId = vm.uiState.value.jellyfin.pendingRowId

        vm.onJellyfinSave()

        assertEquals(serverId, status.records.getValue(rowId).jellyfinServerId)
        assertEquals(rowId, repo.saved.single().id)
    }

    @Test
    fun `an edit never loads the token`() {
        repo.stored["row-9"] = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", token)
        val vm = viewModel(FakePort())
        vm.loadServer("row-9")
        assertEquals("", vm.uiState.value.token)
    }

    @Test
    fun `an address edit asks for sign in again`() {
        repo.stored["row-9"] = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", token)
        status.records["row-9"] = ServerStatusRecord(jellyfinServerId = serverId)
        val vm = viewModel(FakePort())
        vm.loadServer("row-9")
        vm.onBaseUrlChanged("https://other.test/jellyfin")

        vm.saveServer("row-9")

        val saved = repo.saved.single()
        assertEquals("https://other.test/jellyfin", saved.baseUrl)
        assertEquals("", saved.token)
        assertTrue(saved.needsSignIn)
        // Saved as the user's own address change, so the repository removes the old token for sure.
        assertEquals(listOf(saved), repo.userAddressChanges)
    }

    @Test
    fun `a name edit keeps the token`() {
        repo.stored["row-9"] = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", token)
        val vm = viewModel(FakePort())
        vm.loadServer("row-9")
        vm.onNameChanged("Living Room")

        vm.saveServer("row-9")

        val saved = repo.saved.single()
        assertEquals("Living Room", saved.name)
        assertEquals(token, saved.token)
        assertTrue(repo.userAddressChanges.isEmpty())
    }

    @Test
    fun `switching chips clears the Jellyfin password`() {
        val vm = viewModel(FakePort())
        vm.startAdd()
        vm.onSourceChanged(ServerSource.ABS)
        assertEquals("", vm.uiState.value.jellyfin.password)
    }

    @Test
    fun `the state never prints the password`() {
        val vm = viewModel(FakePort())
        vm.startAdd()
        assertFalse(vm.uiState.value.jellyfin.toString().contains("pw-made-up"))
        assertEquals("JellyfinSetupState(step=SIGN_IN)", vm.uiState.value.jellyfin.toString())
    }
}
