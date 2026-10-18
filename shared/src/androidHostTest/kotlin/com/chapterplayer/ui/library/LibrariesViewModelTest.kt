package com.chapterplayer.ui.library

import com.chapterplayer.data.model.LibraryToggle
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerLibraries
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.LibraryCatalog
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.sync.LibrarySync
import com.chapterplayer.data.sync.SyncUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibrariesViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val events = mutableListOf<String>()

    private class FakeCatalog(initial: List<ServerLibraries>) : LibraryCatalog {
        val flow = MutableStateFlow(initial)
        override fun observeServerLibraries(): Flow<List<ServerLibraries>> = flow
    }

    private inner class RecordingServers : ServerRepository {
        var gates = mutableListOf<CompletableDeferred<Unit>>()
        var failWith: Exception? = null
        override fun observeAll(): Flow<List<ServerConfig>> = MutableStateFlow(emptyList())
        override suspend fun getById(id: String): ServerConfig? = null
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {
            events += "start $serverId/$libraryId=$enabled"
            gates.removeFirstOrNull()?.await()
            failWith?.let { throw it }
            events += "set $serverId/$libraryId=$enabled"
        }
    }

    private inner class RecordingSync : LibrarySync {
        override val state = MutableStateFlow(SyncUiState())
        override fun requestSync(serverId: String) {
            events += "sync $serverId"
        }
    }

    private fun server(id: String, source: ServerSource = ServerSource.ABS) =
        ServerConfig(id, source, "Name $id", "http://$id.test", "t")

    private fun lib(id: String, enabled: Boolean = true) = LibraryToggle(id, "Library $id", enabled)

    private class Rig(
        val catalog: FakeCatalog,
        val servers: RecordingServers,
        val sync: RecordingSync,
        val vm: LibrariesViewModel
    )

    private fun rig(initial: List<ServerLibraries>): Rig {
        val catalog = FakeCatalog(initial)
        val servers = RecordingServers()
        val sync = RecordingSync()
        return Rig(catalog, servers, sync, LibrariesViewModel(catalog, servers, sync))
    }

    /** The state flow only runs while someone collects it. */
    private fun kotlinx.coroutines.test.TestScope.watch(r: Rig): StateFlow<LibrariesUiState> {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.uiState.collect { } }
        return r.vm.uiState
    }

    @Test
    fun `the state lists servers and their libraries with the right ticks`() = runTest {
        val r = rig(
            listOf(
                ServerLibraries(server("a"), listOf(lib("l1"), lib("l2", enabled = false))),
                ServerLibraries(server("b", ServerSource.PLEX), listOf(lib("l3")))
            )
        )
        val state = watch(r).value

        assertEquals(listOf("a", "b"), state.servers.map { it.serverId })
        assertEquals("Name a", state.servers[0].title)
        assertEquals("ABS", state.servers[0].sourceLabel)
        assertEquals("PLEX", state.servers[1].sourceLabel)
        assertEquals(listOf(true, false), state.servers[0].libraries.map { it.checked })
        assertEquals(listOf("Library l1", "Library l2"), state.servers[0].libraries.map { it.name })
        assertEquals(listOf(true), state.servers[1].libraries.map { it.checked })
    }

    @Test
    fun `a server without library rows is waiting for the first sync`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), emptyList()), ServerLibraries(server("b"), listOf(lib("l1")))))
        val state = watch(r).value

        assertTrue(state.servers[0].awaitingFirstSync)
        assertFalse(state.servers[1].awaitingFirstSync)
    }

    @Test
    fun `everything is hidden only when no library of any server is ticked`() = runTest {
        val r = rig(
            listOf(
                ServerLibraries(server("a"), listOf(lib("l1", enabled = false))),
                ServerLibraries(server("b"), listOf(lib("l2", enabled = false)))
            )
        )
        val states = watch(r)
        assertTrue(states.value.everythingHidden)

        r.catalog.flow.value = listOf(
            ServerLibraries(server("a"), listOf(lib("l1", enabled = false))),
            ServerLibraries(server("b"), listOf(lib("l2")))
        )
        assertFalse(states.value.everythingHidden)
    }

    @Test
    fun `no libraries at all is not everything hidden`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), emptyList())))
        assertFalse(watch(r).value.everythingHidden)
        val none = rig(emptyList())
        assertFalse(watch(none).value.everythingHidden)
    }

    @Test
    fun `toggling writes the choice and then asks for a sync of that server`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), listOf(lib("l1")))))
        watch(r)

        r.vm.onToggle("a", "l1", false)

        assertEquals(listOf("start a/l1=false", "set a/l1=false", "sync a"), events)
    }

    @Test
    fun `turning a library on also asks for a sync`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), listOf(lib("l1", enabled = false)))))
        watch(r)

        r.vm.onToggle("a", "l1", true)

        assertEquals(listOf("start a/l1=true", "set a/l1=true", "sync a"), events)
    }

    @Test
    fun `two quick toggles on one server both apply one after the other and sync after each`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), listOf(lib("l1"), lib("l2")))))
        watch(r)
        val gate = CompletableDeferred<Unit>()
        r.servers.gates.add(gate)

        r.vm.onToggle("a", "l1", false)
        r.vm.onToggle("a", "l2", false)
        // the second write waits for the first
        assertEquals(listOf("start a/l1=false"), events)

        gate.complete(Unit)

        assertEquals(
            listOf("start a/l1=false", "set a/l1=false", "sync a", "start a/l2=false", "set a/l2=false", "sync a"),
            events
        )
    }

    @Test
    fun `toggles on different servers do not wait for each other`() = runTest {
        val r = rig(
            listOf(
                ServerLibraries(server("a"), listOf(lib("l1"))),
                ServerLibraries(server("b"), listOf(lib("l2")))
            )
        )
        watch(r)
        r.servers.gates.add(CompletableDeferred()) // the first write never finishes

        r.vm.onToggle("a", "l1", false)
        r.vm.onToggle("b", "l2", false)

        assertTrue("set b/l2=false" in events)
        assertTrue("sync b" in events)
        assertFalse("sync a" in events)
    }

    @Test
    fun `a failing write shows an error and does not ask for a sync`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), listOf(lib("l1")))))
        val states = watch(r)
        r.servers.failWith = IllegalStateException("disk full")

        r.vm.onToggle("a", "l1", false)

        assertEquals(listOf("start a/l1=false"), events)
        assertEquals("Could not change the library: disk full", states.value.error)
    }

    @Test
    fun `a later successful toggle clears the earlier error and the next toggle still works`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), listOf(lib("l1")))))
        val states = watch(r)
        r.servers.failWith = IllegalStateException("disk full")
        r.vm.onToggle("a", "l1", false)
        assertNotNull(states.value.error)

        r.servers.failWith = null
        r.vm.onToggle("a", "l1", false)

        assertNull(states.value.error)
        assertTrue("sync a" in events)
    }

    @Test
    fun `a library added later appears ticked`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), listOf(lib("l1")))))
        val states = watch(r)

        r.catalog.flow.value = listOf(ServerLibraries(server("a"), listOf(lib("l1"), lib("l9"))))

        assertEquals(listOf("l1", "l9"), states.value.servers[0].libraries.map { it.id })
        assertTrue(states.value.servers[0].libraries.last().checked)
    }

    @Test
    fun `syncing and sync errors show only after a toggle`() = runTest {
        val r = rig(listOf(ServerLibraries(server("a"), listOf(lib("l1")))))
        val states = watch(r)
        r.sync.state.value = SyncUiState(isSyncing = true, syncError = "Name a: boom")
        assertFalse(states.value.isSyncing)
        assertNull(states.value.error)

        r.vm.onToggle("a", "l1", false)

        assertTrue(states.value.isSyncing)
        assertEquals("Name a: boom", states.value.error)
    }
}
