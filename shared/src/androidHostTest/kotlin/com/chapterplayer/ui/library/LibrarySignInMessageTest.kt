package com.chapterplayer.ui.library

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySignInMessageTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeBooks(var outcome: (ServerConfig) -> Result<Unit> = { Result.success(Unit) }) : BookRepository {
        val syncedIds = mutableListOf<String>()
        override fun observeAllBooks(): Flow<List<AudioBook>> = flowOf(emptyList())
        override fun observeBooksByServer(serverId: String): Flow<List<AudioBook>> = flowOf(emptyList())
        override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> {
            syncedIds += server.id
            return outcome(server)
        }
        override fun observeProgress(bookId: String): Flow<ReadingProgress?> = emptyFlow()
        override fun observeInProgressBooks(): Flow<List<ReadingProgress>> = flowOf(emptyList())
        override suspend fun saveProgress(progress: ReadingProgress) {}
    }

    private class FakeServers(servers: List<ServerConfig>) : ServerRepository {
        val flow = MutableStateFlow(servers)
        override fun observeAll(): Flow<List<ServerConfig>> = flow
        override suspend fun getById(id: String) = flow.value.firstOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
    }

    private fun server(id: String, name: String, token: String) =
        ServerConfig(id, ServerSource.ABS, name, "http://$id.test", token)

    @Test
    fun `a sign-in required failure shows the server name and the plain message`() = runTest {
        val books = FakeBooks { Result.failure(SignInRequiredException("Home")) }
        val vm = LibraryViewModel(books, FakeServers(listOf(server("a", "Home", "t"))))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals("Home: Sign in again in Settings", vm.uiState.value.syncError)
    }

    @Test
    fun `the message uses the display name of the server not the exception`() = runTest {
        val books = FakeBooks { Result.failure(SignInRequiredException("something else")) }
        val vm = LibraryViewModel(books, FakeServers(listOf(server("a", "Home", "t"))))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals("Home: Sign in again in Settings", vm.uiState.value.syncError)
    }

    @Test
    fun `a server with a blank token gets the message without a network call and others still sync`() = runTest {
        val books = FakeBooks()
        val vm = LibraryViewModel(
            books,
            FakeServers(listOf(server("a", "Lost", ""), server("b", "Other", "t")))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals("Lost: Sign in again in Settings", vm.uiState.value.syncError)
        assertEquals(listOf("b"), books.syncedIds)
    }

    @Test
    fun `other errors keep their existing format`() = runTest {
        val books = FakeBooks { Result.failure(IllegalStateException("boom")) }
        val vm = LibraryViewModel(books, FakeServers(listOf(server("a", "Home", "t"))))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals("Home: boom", vm.uiState.value.syncError)
    }

    @Test
    fun `when every server needs signing in the state says so`() = runTest {
        val books = FakeBooks()
        val vm = LibraryViewModel(
            books,
            FakeServers(listOf(server("a", "One", ""), server("b", "Two", "")))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val s = vm.uiState.value
        assertTrue(s.allServersNeedSignIn)
        assertEquals(listOf("One", "Two"), s.signInRequiredNames)
        assertTrue(books.syncedIds.isEmpty())
    }

    @Test
    fun `with one working server the library is not a sign-in state`() = runTest {
        val vm = LibraryViewModel(
            FakeBooks(),
            FakeServers(listOf(server("a", "One", ""), server("b", "Two", "t")))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertFalse(vm.uiState.value.allServersNeedSignIn)
        assertEquals(listOf("One"), vm.uiState.value.signInRequiredNames)
    }
}
