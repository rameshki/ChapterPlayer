package com.chapterplayer.ui.settings

import com.chapterplayer.data.auth.JellyfinSignInError
import com.chapterplayer.data.auth.JellyfinSignInException
import com.chapterplayer.data.auth.JellyfinSignInPort
import com.chapterplayer.data.auth.JellyfinSignInResult
import com.chapterplayer.data.auth.QuickConnectRequest
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_EXPIRED_TEXT
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_FINISH_TIMEOUT_MS
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_READ_TIMEOUT_MS
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_START_TIMEOUT_MS
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_NOT_APPROVED_TEXT
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_OFF_TEXT
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_POLL_MS
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_TIMEOUT_MS
import com.chapterplayer.domain.jellyfin.QuickConnectPoll
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
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinSetupHandlerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

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

        // Quick Connect, scripted: what the start does, what each read says (WAITING once the script runs out), what the sign in does.
        val qc = mutableListOf<String>()
        var qcStart: () -> QuickConnectRequest = { QuickConnectRequest(QC_SECRET, QC_CODE) }
        val qcPolls = ArrayDeque<QuickConnectPoll>()
        var qcFinish: (String) -> JellyfinSignInResult = { row -> result(row) }
        var qcExpected: String? = null

        /** The step ("start", "poll" or "finish") that never answers, like a host that went dark. */
        var qcHang: String? = null
        override suspend fun startQuickConnect(address: String, serverRowId: String, expectedServerId: String?): QuickConnectRequest {
            qc += "start"
            qcExpected = expectedServerId
            if (qcHang == "start") awaitCancellation()
            return qcStart()
        }
        override suspend fun pollQuickConnect(address: String, serverRowId: String, request: QuickConnectRequest): QuickConnectPoll {
            qc += "poll"
            if (qcHang == "poll") awaitCancellation()
            return qcPolls.removeFirstOrNull() ?: QuickConnectPoll.WAITING
        }
        override suspend fun finishQuickConnect(
            address: String, serverRowId: String, request: QuickConnectRequest, expectedServerId: String?,
        ): JellyfinSignInResult {
            qc += "finish"
            if (qcHang == "finish") awaitCancellation()
            return qcFinish(serverRowId)
        }
    }

    private val QC_SECRET = "feedfacefeedfacefeedfacefeedface"
    private val QC_CODE = "482916"

    /** Lets the virtual clock run [ms] (the wait polls every five seconds), including what is due exactly then. */
    private fun tick(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
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

    // ---- Quick Connect (J3, spec 5.1) ----

    private fun ServerSetupViewModel.startQuickConnectAdd() {
        onSourceChanged(ServerSource.JELLYFIN)
        onJellyfinAddressChanged("https://jf.test/jellyfin/")
        onJellyfinQuickConnect()
    }

    @Test
    fun `Quick Connect shows the code while it waits and signs nobody in yet`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.startQuickConnectAdd()

        val s = vm.uiState.value.jellyfin
        assertEquals(QuickConnectUi(code = QC_CODE), s.quickConnect)
        assertEquals(QC_CODE, s.quickConnect?.code)
        assertTrue(s.isSigningIn)
        assertEquals(JellyfinStep.SIGN_IN, s.step)
        assertEquals(listOf("start"), port.qc)
        assertTrue(port.calls.isEmpty(), "no password sign-in")
    }

    @Test
    fun `an approval signs in and lists the libraries like a password does`() {
        val port = FakePort().apply { qcPolls += listOf(QuickConnectPoll.WAITING, QuickConnectPoll.APPROVED) }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()

        tick(QUICK_CONNECT_POLL_MS)
        assertEquals(JellyfinStep.SIGN_IN, vm.uiState.value.jellyfin.step)
        tick(QUICK_CONNECT_POLL_MS)

        val s = vm.uiState.value.jellyfin
        assertEquals(listOf("start", "poll", "poll", "finish"), port.qc)
        assertEquals(JellyfinStep.LIBRARIES, s.step)
        assertEquals(listOf(LibraryChoice("v1", "Audiobooks", true), LibraryChoice("v2", "Kids Books", true)), s.libraries)
        assertNull(s.quickConnect)
        assertFalse(s.isSigningIn)
        assertNull(s.signInError)
    }

    @Test
    fun `saving after Quick Connect stores the token and the server id`() {
        val port = FakePort().apply { qcPolls += QuickConnectPoll.APPROVED }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        tick(QUICK_CONNECT_POLL_MS)

        vm.onJellyfinSave()

        val saved = repo.saved.single()
        assertEquals(token, saved.token)
        assertEquals("https://jf.test/jellyfin", saved.baseUrl)
        assertEquals(serverId, status.records.getValue(saved.id).jellyfinServerId)
        assertTrue(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun `the row id of the start is the one signed in with`() {
        val port = FakePort().apply { qcPolls += QuickConnectPoll.APPROVED }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        val rowId = vm.uiState.value.jellyfin.pendingRowId
        assertTrue(rowId.isNotBlank())
        tick(QUICK_CONNECT_POLL_MS)
        vm.onJellyfinSave()
        assertEquals(rowId, repo.saved.single().id)
    }

    @Test
    fun `five minutes without an approval expire the code`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.startQuickConnectAdd()

        tick(QUICK_CONNECT_TIMEOUT_MS - 1)
        assertNotNull(vm.uiState.value.jellyfin.quickConnect, "still waiting just before the end")
        tick(1)

        val s = vm.uiState.value.jellyfin
        assertEquals(QUICK_CONNECT_EXPIRED_TEXT, s.signInError)
        assertNull(s.quickConnect)
        assertFalse(s.isSigningIn)
        assertEquals((QUICK_CONNECT_TIMEOUT_MS / QUICK_CONNECT_POLL_MS).toInt(), port.qc.count { it == "poll" })
        assertFalse("finish" in port.qc)
        // nothing keeps polling afterwards
        tick(QUICK_CONNECT_TIMEOUT_MS)
        assertEquals((QUICK_CONNECT_TIMEOUT_MS / QUICK_CONNECT_POLL_MS).toInt(), port.qc.count { it == "poll" })
    }

    @Test
    fun `a request the server forgot expires the code at the next read`() {
        val port = FakePort().apply { qcPolls += QuickConnectPoll.UNKNOWN }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        tick(QUICK_CONNECT_POLL_MS)

        assertEquals(QUICK_CONNECT_EXPIRED_TEXT, vm.uiState.value.jellyfin.signInError)
        assertNull(vm.uiState.value.jellyfin.quickConnect)
        assertEquals(listOf("start", "poll"), port.qc)
    }

    @Test
    fun `a failed read is said and tried again until the approval comes`() {
        val port = FakePort().apply {
            qcPolls += listOf(QuickConnectPoll.UNREACHABLE, QuickConnectPoll.UNREACHABLE, QuickConnectPoll.APPROVED)
        }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()

        tick(QUICK_CONNECT_POLL_MS)
        assertEquals(QuickConnectUi(code = QC_CODE, serverTrouble = true), vm.uiState.value.jellyfin.quickConnect)
        tick(QUICK_CONNECT_POLL_MS)
        assertEquals(true, vm.uiState.value.jellyfin.quickConnect?.serverTrouble)
        tick(QUICK_CONNECT_POLL_MS)

        assertEquals(JellyfinStep.LIBRARIES, vm.uiState.value.jellyfin.step)
        assertNull(vm.uiState.value.jellyfin.quickConnect)
    }

    @Test
    fun `the trouble line goes when the server answers again`() {
        val port = FakePort().apply { qcPolls += listOf(QuickConnectPoll.UNREACHABLE, QuickConnectPoll.WAITING) }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        tick(QUICK_CONNECT_POLL_MS)
        assertEquals(true, vm.uiState.value.jellyfin.quickConnect?.serverTrouble)
        tick(QUICK_CONNECT_POLL_MS)
        assertEquals(false, vm.uiState.value.jellyfin.quickConnect?.serverTrouble)
    }

    @Test
    fun `Cancel stops the polling, forgets the code and calls nobody`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        tick(QUICK_CONNECT_POLL_MS * 2)
        val polls = port.qc.count { it == "poll" }
        assertEquals(2, polls)

        vm.onJellyfinQuickConnectCancel()

        val s = vm.uiState.value.jellyfin
        assertNull(s.quickConnect)
        assertFalse(s.isSigningIn)
        assertNull(s.signInError)
        tick(QUICK_CONNECT_TIMEOUT_MS * 2)
        assertEquals(polls, port.qc.count { it == "poll" }, "no read after Cancel")
        assertFalse("finish" in port.qc)
    }

    @Test
    fun `leaving the screen ends the wait`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        tick(QUICK_CONNECT_POLL_MS)

        vm.onJellyfinSecretsDiscarded()

        assertNull(vm.uiState.value.jellyfin.quickConnect)
        val polls = port.qc.count { it == "poll" }
        tick(QUICK_CONNECT_TIMEOUT_MS)
        assertEquals(polls, port.qc.count { it == "poll" })
    }

    @Test
    fun `only one wait runs at a time`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        vm.onJellyfinQuickConnect()
        vm.onJellyfinQuickConnect()
        vm.onJellyfinSignIn() // a password sign in during the wait is ignored too

        assertEquals(1, port.qc.count { it == "start" })
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `a new code can be asked for after Cancel`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        vm.onJellyfinQuickConnectCancel()
        vm.onJellyfinQuickConnect()
        assertEquals(2, port.qc.count { it == "start" })
        assertEquals(QC_CODE, vm.uiState.value.jellyfin.quickConnect?.code)
    }

    @Test
    fun `Quick Connect that is off says so and polls nothing`() {
        val port = FakePort().apply { qcStart = { throw JellyfinSignInException(JellyfinSignInError.QuickConnectOff) } }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()

        val s = vm.uiState.value.jellyfin
        assertEquals(QUICK_CONNECT_OFF_TEXT, s.signInError)
        assertNull(s.quickConnect)
        assertFalse(s.isSigningIn)
        tick(QUICK_CONNECT_TIMEOUT_MS)
        assertEquals(listOf("start"), port.qc)
    }

    @Test
    fun `a start that cannot reach the server says so`() {
        val port = FakePort().apply { qcStart = { throw JellyfinSignInException(JellyfinSignInError.Unreachable) } }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        assertEquals("Couldn't reach the server. Check the address and your network.", vm.uiState.value.jellyfin.signInError)
        assertNull(vm.uiState.value.jellyfin.quickConnect)
    }

    @Test
    fun `a sign in the server refuses after the approval says it was not approved`() {
        val port = FakePort().apply {
            qcPolls += QuickConnectPoll.APPROVED
            qcFinish = { throw JellyfinSignInException(JellyfinSignInError.QuickConnectNotApproved) }
        }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        tick(QUICK_CONNECT_POLL_MS)

        val s = vm.uiState.value.jellyfin
        assertEquals(QUICK_CONNECT_NOT_APPROVED_TEXT, s.signInError)
        assertNull(s.quickConnect)
        assertEquals(JellyfinStep.SIGN_IN, s.step)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `a blank address does not start a wait`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.onSourceChanged(ServerSource.JELLYFIN)
        vm.onJellyfinQuickConnect()
        assertNull(vm.uiState.value.jellyfin.quickConnect)
        assertEquals(JELLYFIN_INVALID_ADDRESS_TEXT, vm.uiState.value.jellyfin.signInError)
        assertFalse(vm.uiState.value.jellyfin.isSigningIn)
        assertTrue(port.qc.isEmpty())
    }

    @Test
    fun `neither the code nor the secret reaches the state's text`() {
        val port = FakePort()
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        val text = vm.uiState.value.jellyfin.toString() + vm.uiState.value.jellyfin.quickConnect.toString()
        assertFalse(QC_CODE in text || QC_SECRET in text, text)
        assertFalse(QC_SECRET in QuickConnectRequest(QC_SECRET, QC_CODE).toString())
    }

    @Test
    fun `Quick Connect in sign in again checks the stored server and saves the new token`() {
        val stored = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", "")
        repo.stored[stored.id] = stored
        status.records[stored.id] = ServerStatusRecord(jellyfinServerId = serverId)
        val port = FakePort().apply { qcPolls += QuickConnectPoll.APPROVED }
        val vm = viewModel(port)
        vm.loadServer(stored.id)

        vm.onJellyfinQuickConnectAgain(stored.id)
        assertEquals(QC_CODE, vm.uiState.value.jellyfin.quickConnect?.code)
        tick(QUICK_CONNECT_POLL_MS)

        assertEquals(serverId, port.qcExpected)
        assertEquals(token, repo.saved.single().token)
        assertEquals("row-9", repo.saved.single().id)
        assertTrue(vm.uiState.value.savedSuccessfully)
        assertNull(vm.uiState.value.jellyfin.quickConnect)
        assertTrue(port.calls.isEmpty(), "no password was used")
    }

    @Test
    fun `Quick Connect in sign in again on another server stores nothing`() {
        val stored = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", "")
        repo.stored[stored.id] = stored
        status.records[stored.id] = ServerStatusRecord(jellyfinServerId = serverId)
        val port = FakePort().apply { qcStart = { throw JellyfinSignInException(JellyfinSignInError.OtherServer) } }
        val vm = viewModel(port)
        vm.loadServer(stored.id)

        vm.onJellyfinQuickConnectAgain(stored.id)

        assertEquals(JELLYFIN_OTHER_SERVER_TEXT, vm.uiState.value.jellyfin.signInError)
        assertTrue(repo.saved.isEmpty())
        assertFalse(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun `Quick Connect in sign in again without a stored server id is refused`() {
        repo.stored["row-9"] = ServerConfig("row-9", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", "")
        val port = FakePort()
        val vm = viewModel(port)
        vm.loadServer("row-9")

        vm.onJellyfinQuickConnectAgain("row-9")

        assertTrue(port.qc.isEmpty())
        assertEquals(JELLYFIN_ID_MISSING_TEXT, vm.uiState.value.jellyfin.signInError)
    }

    // ---- found by the independent review of Quick Connect ----

    @Test
    fun `the token is saved under the address it was signed in to, not the one the field holds by then`() {
        val port = FakePort().apply { qcPolls += QuickConnectPoll.APPROVED }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        vm.onJellyfinAddressChanged("https://other.test") // edited during the wait
        tick(QUICK_CONNECT_POLL_MS)

        vm.onJellyfinSave()

        assertEquals("https://jf.test/jellyfin", repo.saved.single().baseUrl)
    }

    @Test
    fun `a password sign in saves under its own address too`() {
        val vm = viewModel(FakePort())
        vm.startAdd()
        vm.onJellyfinSignIn()
        vm.onJellyfinAddressChanged("https://other.test")

        vm.onJellyfinSave()

        assertEquals("https://jf.test/jellyfin", repo.saved.single().baseUrl)
    }

    @Test
    fun `a host that goes dark cannot stretch the five minutes`() {
        val port = FakePort().apply { qcHang = "poll" }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()

        // each read gives up after its limit and counts as time waited: one round is the 5 s delay plus the 5 s limit
        tick(QUICK_CONNECT_POLL_MS + QUICK_CONNECT_READ_TIMEOUT_MS)
        assertEquals(true, vm.uiState.value.jellyfin.quickConnect?.serverTrouble)
        tick(QUICK_CONNECT_TIMEOUT_MS - (QUICK_CONNECT_POLL_MS + QUICK_CONNECT_READ_TIMEOUT_MS))

        val s = vm.uiState.value.jellyfin
        assertEquals(QUICK_CONNECT_EXPIRED_TEXT, s.signInError)
        assertNull(s.quickConnect)
        assertFalse(s.isSigningIn)
        assertEquals((QUICK_CONNECT_TIMEOUT_MS / (QUICK_CONNECT_POLL_MS + QUICK_CONNECT_READ_TIMEOUT_MS)).toInt(), port.qc.count { it == "poll" })
    }

    @Test
    fun `a start that never answers is a network failure after its limit`() {
        val port = FakePort().apply { qcHang = "start" }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        assertEquals(QuickConnectUi(), vm.uiState.value.jellyfin.quickConnect) // no code yet

        tick(QUICK_CONNECT_START_TIMEOUT_MS)

        assertEquals("Couldn't reach the server. Check the address and your network.", vm.uiState.value.jellyfin.signInError)
        assertNull(vm.uiState.value.jellyfin.quickConnect)
        assertFalse(vm.uiState.value.jellyfin.isSigningIn)
    }

    @Test
    fun `a final sign in that never answers is a network failure after its limit and saves nothing`() {
        val port = FakePort().apply {
            qcPolls += QuickConnectPoll.APPROVED
            qcHang = "finish"
        }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        tick(QUICK_CONNECT_POLL_MS)
        assertNull(vm.uiState.value.jellyfin.signInError)

        tick(QUICK_CONNECT_FINISH_TIMEOUT_MS)

        assertEquals("Couldn't reach the server. Check the address and your network.", vm.uiState.value.jellyfin.signInError)
        assertNull(vm.uiState.value.jellyfin.quickConnect)
        assertEquals(JellyfinStep.SIGN_IN, vm.uiState.value.jellyfin.step)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `Cancel while the approved sign in is being finished ends it`() {
        val port = FakePort().apply {
            qcPolls += QuickConnectPoll.APPROVED
            qcHang = "finish"
        }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        tick(QUICK_CONNECT_POLL_MS)
        assertTrue("finish" in port.qc)

        vm.onJellyfinQuickConnectCancel()

        val s = vm.uiState.value.jellyfin
        assertNull(s.quickConnect)
        assertFalse(s.isSigningIn)
        assertNull(s.signInError)
        tick(QUICK_CONNECT_FINISH_TIMEOUT_MS * 2)
        assertNull(vm.uiState.value.jellyfin.signInError, "the cancelled sign in says nothing afterwards")
        assertEquals(JellyfinStep.SIGN_IN, vm.uiState.value.jellyfin.step)
    }

    @Test
    fun `Cancel before the first code arrives ends the wait`() {
        val port = FakePort().apply { qcHang = "start" }
        val vm = viewModel(port)
        vm.startQuickConnectAdd()
        assertEquals(QuickConnectUi(), vm.uiState.value.jellyfin.quickConnect)

        vm.onJellyfinQuickConnectCancel()

        assertNull(vm.uiState.value.jellyfin.quickConnect)
        assertFalse(vm.uiState.value.jellyfin.isSigningIn)
        tick(QUICK_CONNECT_START_TIMEOUT_MS * 2)
        assertNull(vm.uiState.value.jellyfin.signInError)
    }

    @Test
    fun `Cancel does not touch a password sign in`() {
        val vm = viewModel(FakePort())
        vm.startAdd()
        vm.onJellyfinSignIn()
        vm.onJellyfinQuickConnectCancel()
        assertEquals(JellyfinStep.LIBRARIES, vm.uiState.value.jellyfin.step)
    }
}
