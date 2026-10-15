package com.chapterplayer.ui.settings

import com.chapterplayer.data.auth.AbsLibraryInfo
import com.chapterplayer.data.auth.AbsSignInError
import com.chapterplayer.data.auth.AbsSignInException
import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.auth.AbsSignInResult
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.repository.ServerRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AbsSetupViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val libraries = listOf(
        AbsLibraryInfo("lib1", "Books", "book"),
        AbsLibraryInfo("lib2", "More Books", "book"),
    )

    private fun result(refresh: String? = "refresh-1") =
        AbsSignInResult("http://abs.test", "token-1", refresh, libraries)

    private class FakePort(var outcome: () -> AbsSignInResult) : AbsSignInPort {
        var passwordCalls = 0
        var apiKeyCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        var lastApiKey: String? = null

        override suspend fun withPassword(address: String, username: String, password: String): AbsSignInResult {
            passwordCalls++
            gate?.await()
            return outcome()
        }

        override suspend fun withApiKey(address: String, apiKey: String): AbsSignInResult {
            apiKeyCalls++
            lastApiKey = apiKey
            return outcome()
        }
    }

    private class FakeRepository(var failSave: Boolean = false) : ServerRepository {
        val saved = mutableListOf<ServerConfig>()
        val stored = mutableMapOf<String, ServerConfig>()
        override fun observeAll(): Flow<List<ServerConfig>> = emptyFlow()
        override suspend fun getById(id: String): ServerConfig? = stored[id]
        override suspend fun save(config: ServerConfig) {
            if (failSave) error("keystore unavailable")
            saved += config
            stored[config.id] = config
        }
        override suspend fun delete(id: String) {}
    }

    private fun viewModel(port: AbsSignInPort, repo: ServerRepository = FakeRepository()): ServerSetupViewModel {
        val plex = PlexTvApiService(
            HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { installApiDefaults(isDebuggable = false) }
        )
        return ServerSetupViewModel(repo, plex, object : PlexClientIdentifier { override val value = "client-1" }, port)
    }

    private fun ServerSetupViewModel.fillForm() {
        onAbsAddressChanged("abs.test")
        onAbsUsernameChanged("reader")
        onAbsPasswordChanged("pw-made-up")
    }

    @Test
    fun `signing in moves to the libraries step with every library ticked and clears the password`() {
        val vm = viewModel(FakePort { result() })
        vm.fillForm()

        vm.onAbsSignIn()

        val s = vm.uiState.value.abs
        assertEquals(AbsStep.LIBRARIES, s.step)
        assertEquals(listOf(LibraryChoice("lib1", "Books", true), LibraryChoice("lib2", "More Books", true)), s.libraries)
        assertEquals("", s.password)
        assertNull(s.signInError)
        assertFalse(s.isSigningIn)
    }

    @Test
    fun `each sign-in error shows its plain message and stays on the sign-in step`() {
        val cases = listOf(
            AbsSignInError.Unreachable to "Couldn't reach the server. Check the address and your network.",
            AbsSignInError.NotAudiobookshelf to "That address doesn't look like an Audiobookshelf server.",
            AbsSignInError.LocalLoginOff to "This server only allows OpenID sign-in, which isn't supported yet.",
            AbsSignInError.WrongCredentials to "The username or password is wrong.",
            AbsSignInError.TooManyAttempts to "Too many attempts. Wait a few minutes and try again.",
            AbsSignInError.Rejected to "The server didn't accept that API token.",
            AbsSignInError.ServerError(502) to "The server returned an error (code 502).",
            AbsSignInError.NoBookLibraries to "This server has no audiobook libraries.",
        )
        for ((error, message) in cases) {
            val repo = FakeRepository()
            val vm = viewModel(FakePort { throw AbsSignInException(error) }, repo)
            vm.fillForm()

            vm.onAbsSignIn()

            val s = vm.uiState.value.abs
            assertEquals(message, s.signInError)
            assertEquals(AbsStep.SIGN_IN, s.step)
            assertFalse(s.isSigningIn)
            assertTrue(repo.saved.isEmpty())
        }
    }

    @Test
    fun `an unexpected exception shows a generic message and never its own text`() {
        val vm = viewModel(FakePort { throw IllegalStateException("secret-token-text") })
        vm.fillForm()

        vm.onAbsSignIn()

        val s = vm.uiState.value.abs
        assertEquals("Couldn't sign in. Please try again.", s.signInError)
        assertEquals(AbsStep.SIGN_IN, s.step)
        assertFalse(s.isSigningIn)
    }

    @Test
    fun `two quick taps on sign in start one request`() {
        val port = FakePort { result() }
        port.gate = CompletableDeferred()
        val vm = viewModel(port)
        vm.fillForm()

        vm.onAbsSignIn()
        vm.onAbsSignIn()
        assertTrue(vm.uiState.value.abs.isSigningIn)
        port.gate!!.complete(Unit)

        assertEquals(1, port.passwordCalls)
        assertEquals(AbsStep.LIBRARIES, vm.uiState.value.abs.step)
    }

    @Test
    fun `the api key path uses the key and stores no refresh token`() {
        val port = FakePort { result(refresh = null) }
        val repo = FakeRepository()
        val vm = viewModel(port, repo)
        vm.onAbsAddressChanged("abs.test")
        vm.onAbsUseApiKeyChanged(true)
        vm.onAbsApiKeyChanged("key-made-up")

        vm.onAbsSignIn()
        vm.onAbsSave()

        assertEquals(0, port.passwordCalls)
        assertEquals("key-made-up", port.lastApiKey)
        assertNull(repo.saved.single().refreshToken)
    }

    @Test
    fun `save is not possible while every library is unticked`() {
        val repo = FakeRepository()
        val vm = viewModel(FakePort { result() }, repo)
        vm.fillForm()
        vm.onAbsSignIn()

        vm.onAbsLibraryToggled("lib1")
        vm.onAbsLibraryToggled("lib2")
        assertFalse(vm.uiState.value.abs.canSave)
        vm.onAbsSave()

        assertTrue(repo.saved.isEmpty())
        assertFalse(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun `saving writes the tokens and the unticked libraries as exclusions`() {
        val repo = FakeRepository()
        val vm = viewModel(FakePort { result() }, repo)
        vm.fillForm()
        vm.onNameChanged("Home")
        vm.onAbsSignIn()
        vm.onAbsLibraryToggled("lib2")

        vm.onAbsSave()

        val saved = repo.saved.single()
        assertEquals(ServerSource.ABS, saved.source)
        assertEquals("Home", saved.name)
        assertEquals("http://abs.test", saved.baseUrl)
        assertEquals("token-1", saved.token)
        assertEquals("refresh-1", saved.refreshToken)
        assertEquals(setOf("lib2"), saved.excludedLibraryIds)
        assertNull(saved.libraryId)
        assertTrue(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun `an empty display name defaults to the host`() {
        val repo = FakeRepository()
        val vm = viewModel(FakePort { result() }, repo)
        vm.fillForm()
        vm.onAbsSignIn()

        vm.onAbsSave()

        assertEquals("abs.test", repo.saved.single().name)
    }

    @Test
    fun `a failed save keeps the ticks and a retry succeeds`() {
        val repo = FakeRepository(failSave = true)
        val vm = viewModel(FakePort { result() }, repo)
        vm.fillForm()
        vm.onAbsSignIn()
        vm.onAbsLibraryToggled("lib2")

        vm.onAbsSave()

        val s = vm.uiState.value
        assertEquals(AbsStep.LIBRARIES, s.abs.step)
        assertEquals("Couldn't save this server securely on your phone. Please try again.", s.abs.saveError)
        assertFalse(s.savedSuccessfully)
        assertFalse(s.abs.isSaving)
        assertEquals(listOf(true, false), s.abs.libraries.map { it.enabled })

        repo.failSave = false
        vm.onAbsSave()

        assertTrue(vm.uiState.value.savedSuccessfully)
        assertEquals(setOf("lib2"), repo.saved.single().excludedLibraryIds)
    }

    @Test
    fun `signing in again after an error works and clears the error`() {
        var fail = true
        val vm = viewModel(FakePort { if (fail) throw AbsSignInException(AbsSignInError.WrongCredentials) else result() })
        vm.fillForm()
        vm.onAbsSignIn()
        assertEquals("The username or password is wrong.", vm.uiState.value.abs.signInError)

        fail = false
        vm.onAbsSignIn()

        assertNull(vm.uiState.value.abs.signInError)
        assertEquals(AbsStep.LIBRARIES, vm.uiState.value.abs.step)
    }

    @Test
    fun `switching to the api key clears a typed password`() {
        val vm = viewModel(FakePort { result() })
        vm.fillForm()

        vm.onAbsUseApiKeyChanged(true)

        assertEquals("", vm.uiState.value.abs.password)
    }

    @Test
    fun `switching to Plex clears the typed password and key`() {
        val vm = viewModel(FakePort { result() })
        vm.fillForm()
        vm.onAbsApiKeyChanged("key-made-up")

        vm.onSourceChanged(ServerSource.PLEX)

        assertEquals("", vm.uiState.value.abs.password)
        assertEquals("", vm.uiState.value.abs.apiKey)
    }

    @Test
    fun `the api key is cleared after a successful sign-in`() {
        val vm = viewModel(FakePort { result(refresh = null) })
        vm.onAbsAddressChanged("abs.test")
        vm.onAbsUseApiKeyChanged(true)
        vm.onAbsApiKeyChanged("key-made-up")

        vm.onAbsSignIn()

        assertEquals("", vm.uiState.value.abs.apiKey)
    }

    @Test
    fun `a second save after success writes nothing because the result is dropped`() {
        val repo = FakeRepository()
        val vm = viewModel(FakePort { result() }, repo)
        vm.fillForm()
        vm.onAbsSignIn()
        vm.onAbsSave()

        vm.onAbsSave()

        assertEquals(1, repo.saved.size)
    }

    @Test
    fun `editing a server that no longer exists says so`() {
        val vm = viewModel(FakePort { result() }, FakeRepository())
        vm.onSourceChanged(ServerSource.ABS)
        vm.onNameChanged("New")
        vm.onBaseUrlChanged("http://new.test")

        vm.saveServer("gone")

        val s = vm.uiState.value
        assertEquals("That server no longer exists.", s.saveError)
        assertFalse(s.savedSuccessfully)
        assertFalse(s.isSaving)
    }

    @Test
    fun `editing an Audiobookshelf server with a bad address shows an error and saves nothing`() {
        val repo = FakeRepository()
        repo.stored["s1"] = ServerConfig("s1", ServerSource.ABS, "Old", "http://old.test", "token-1")
        val vm = viewModel(FakePort { result() }, repo)
        vm.loadServer("s1")
        vm.onBaseUrlChanged("ftp://bad")

        vm.saveServer("s1")

        assertEquals("Enter a valid server address.", vm.uiState.value.saveError)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `an edit keeps the stored source even if the form source was changed`() {
        val repo = FakeRepository()
        repo.stored["s1"] = ServerConfig(
            "s1", ServerSource.ABS, "Old", "http://old.test", "token-1", refreshToken = "refresh-1"
        )
        val vm = viewModel(FakePort { result() }, repo)
        vm.loadServer("s1")
        vm.onSourceChanged(ServerSource.PLEX)
        vm.onNameChanged("New")
        vm.onBaseUrlChanged("http://new.test")

        vm.saveServer("s1")

        val saved = repo.saved.single()
        assertEquals(ServerSource.ABS, saved.source)
        assertEquals("token-1", saved.token)
        assertEquals("refresh-1", saved.refreshToken)
    }

    @Test
    fun `going back from the libraries step keeps the address and username but not the password`() {
        val vm = viewModel(FakePort { result() })
        vm.fillForm()
        vm.onAbsSignIn()

        vm.onAbsBack()

        val s = vm.uiState.value.abs
        assertEquals(AbsStep.SIGN_IN, s.step)
        assertEquals("abs.test", s.address)
        assertEquals("reader", s.username)
        assertEquals("", s.password)
    }

    @Test
    fun `editing an Audiobookshelf server keeps its refresh token and exclusions`() {
        val repo = FakeRepository()
        repo.stored["s1"] = ServerConfig(
            "s1", ServerSource.ABS, "Old", "http://old.test", "token-1",
            libraryId = null, refreshToken = "refresh-1", excludedLibraryIds = setOf("lib2")
        )
        val port = FakePort { result() }
        val vm = viewModel(port, repo)
        vm.loadServer("s1")
        vm.onNameChanged("New")
        vm.onBaseUrlChanged("http://new.test/")

        vm.saveServer("s1")

        val saved = repo.saved.single()
        assertEquals("New", saved.name)
        assertEquals("http://new.test", saved.baseUrl)
        assertEquals("token-1", saved.token)
        assertEquals("refresh-1", saved.refreshToken)
        assertEquals(setOf("lib2"), saved.excludedLibraryIds)
        assertEquals(0, port.passwordCalls)
    }

    // ── Sign in again (edit mode) ───────────────────────────────────────────

    private fun storedServer(repo: FakeRepository, token: String = "old-token") {
        repo.stored["s1"] = ServerConfig(
            "s1", ServerSource.ABS, "Home", "http://old.test", token,
            libraryId = "libX", refreshToken = "old-refresh", excludedLibraryIds = setOf("lib2")
        )
    }

    @Test
    fun `signing in again keeps id name libraries and replaces both tokens`() {
        val repo = FakeRepository()
        storedServer(repo, token = "")
        val vm = viewModel(FakePort { result() }, repo)
        vm.loadServer("s1")
        vm.onAbsUsernameChanged("reader")
        vm.onAbsPasswordChanged("pw-made-up")

        vm.onAbsSignInAgain("s1")

        val saved = repo.saved.single()
        assertEquals("s1", saved.id)
        assertEquals("Home", saved.name)
        assertEquals("http://abs.test", saved.baseUrl)
        assertEquals("token-1", saved.token)
        assertEquals("refresh-1", saved.refreshToken)
        assertEquals(setOf("lib2"), saved.excludedLibraryIds)
        assertEquals("libX", saved.libraryId)
        assertTrue(vm.uiState.value.savedSuccessfully)
        assertEquals("", vm.uiState.value.abs.password)
        assertFalse(vm.uiState.value.abs.isSigningIn)
    }

    @Test
    fun `signing in again uses an edited display name`() {
        val repo = FakeRepository()
        storedServer(repo)
        val vm = viewModel(FakePort { result() }, repo)
        vm.loadServer("s1")
        vm.onNameChanged("Renamed")
        vm.onAbsUsernameChanged("reader")
        vm.onAbsPasswordChanged("pw-made-up")

        vm.onAbsSignInAgain("s1")

        assertEquals("Renamed", repo.saved.single().name)
    }

    @Test
    fun `the edit form never carries the stored token`() {
        val repo = FakeRepository()
        storedServer(repo)
        val vm = viewModel(FakePort { result() }, repo)

        vm.loadServer("s1")

        val abs = vm.uiState.value.abs
        assertEquals("", abs.password)
        assertEquals("", abs.apiKey)
        assertEquals("", abs.username)
        assertFalse(vm.uiState.value.toString().contains("old-token"))
        assertFalse(abs.toString().contains("old-token"))
    }

    @Test
    fun `signing in again with an api token drops the old refresh token`() {
        val repo = FakeRepository()
        storedServer(repo)
        val port = FakePort { result(refresh = null) }
        val vm = viewModel(port, repo)
        vm.loadServer("s1")
        vm.onAbsUseApiKeyChanged(true)
        vm.onAbsApiKeyChanged("key-made-up")

        vm.onAbsSignInAgain("s1")

        assertEquals(0, port.passwordCalls)
        assertEquals("key-made-up", port.lastApiKey)
        val saved = repo.saved.single()
        assertEquals("token-1", saved.token)
        assertNull(saved.refreshToken)
        assertEquals(setOf("lib2"), saved.excludedLibraryIds)
        assertEquals("", vm.uiState.value.abs.apiKey)
    }

    @Test
    fun `a wrong password on sign in again shows the message and saves nothing`() {
        val repo = FakeRepository()
        storedServer(repo)
        val vm = viewModel(FakePort { throw AbsSignInException(AbsSignInError.WrongCredentials) }, repo)
        vm.loadServer("s1")
        vm.onAbsUsernameChanged("reader")
        vm.onAbsPasswordChanged("pw-made-up")

        vm.onAbsSignInAgain("s1")

        val s = vm.uiState.value
        assertEquals("The username or password is wrong.", s.abs.signInError)
        assertTrue(repo.saved.isEmpty())
        assertFalse(s.savedSuccessfully)
        assertFalse(s.abs.isSigningIn)
    }

    @Test
    fun `signing in again for a server that is gone says so and saves nothing`() {
        val repo = FakeRepository()
        val vm = viewModel(FakePort { result() }, repo)
        vm.onAbsUsernameChanged("reader")
        vm.onAbsPasswordChanged("pw-made-up")
        vm.onBaseUrlChanged("http://old.test")

        vm.onAbsSignInAgain("gone")

        val s = vm.uiState.value
        assertEquals("That server no longer exists.", s.saveError)
        assertTrue(repo.saved.isEmpty())
        assertFalse(s.savedSuccessfully)
        assertFalse(s.abs.isSigningIn)
    }

    @Test
    fun `a failed save after signing in again shows the secure storage message`() {
        val repo = FakeRepository()
        storedServer(repo)
        val vm = viewModel(FakePort { result() }, repo)
        vm.loadServer("s1")
        vm.onAbsUsernameChanged("reader")
        vm.onAbsPasswordChanged("pw-made-up")
        repo.failSave = true

        vm.onAbsSignInAgain("s1")

        val s = vm.uiState.value
        assertEquals("Couldn't save this server securely on your phone. Please try again.", s.saveError)
        assertFalse(s.savedSuccessfully)
        assertFalse(s.abs.isSigningIn)
    }

    @Test
    fun `an invalid address on sign in again shows an error without a network call`() {
        val repo = FakeRepository()
        storedServer(repo)
        val port = FakePort { result() }
        val vm = viewModel(port, repo)
        vm.loadServer("s1")
        vm.onBaseUrlChanged("ftp://bad")
        vm.onAbsUsernameChanged("reader")
        vm.onAbsPasswordChanged("pw-made-up")

        vm.onAbsSignInAgain("s1")

        assertEquals("Enter a valid server address.", vm.uiState.value.abs.signInError)
        assertEquals(0, port.passwordCalls)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `two quick taps on sign in again start one request`() {
        val repo = FakeRepository()
        storedServer(repo)
        val port = FakePort { result() }
        port.gate = CompletableDeferred()
        val vm = viewModel(port, repo)
        vm.loadServer("s1")
        vm.onAbsUsernameChanged("reader")
        vm.onAbsPasswordChanged("pw-made-up")

        vm.onAbsSignInAgain("s1")
        vm.onAbsSignInAgain("s1")
        assertTrue(vm.uiState.value.abs.isSigningIn)
        port.gate!!.complete(Unit)

        assertEquals(1, port.passwordCalls)
        assertEquals(1, repo.saved.size)
    }
}
