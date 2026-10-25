package com.chapterplayer.data.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionKind
import com.chapterplayer.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerStatusStoreTest {

    private val candidate = PlexCandidate("http://192.0.2.11:32400", PlexConnectionKind.LOCAL_ADDRESS)

    @Test
    fun `an unknown server has an empty record`() = runTest {
        val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())

        assertEquals(ServerStatusRecord(), store.get("s1"))
        assertEquals(emptyMap(), store.all.first())
    }

    @Test
    fun `updates are saved per server and read back`() = runTest {
        val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())

        store.update("s1") { it.copy(candidates = listOf(StoredCandidate.of(candidate)), chosenUri = candidate.uri, lastSuccessAtMs = 5) }
        store.update("s2") { it.copy(lastErrorText = "timeout", lastErrorAtMs = 9) }
        store.update("s1") { it.copy(lastProbeAtMs = 7) }

        val s1 = store.get("s1")
        assertEquals(listOf(candidate), s1.candidates.map { it.toCandidate() })
        assertEquals(candidate.uri, s1.chosenUri)
        assertEquals(5L, s1.lastSuccessAtMs)
        assertEquals(7L, s1.lastProbeAtMs)
        assertEquals(setOf("s1", "s2"), store.all.first().keys)
        assertEquals("timeout", store.all.first().getValue("s2").lastErrorText)
    }

    @Test
    fun `removing a server forgets its record`() = runTest {
        val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())
        store.update("s1") { it.copy(lastSuccessAtMs = 1) }

        store.remove("s1")

        assertEquals(ServerStatusRecord(), store.get("s1"))
        assertTrue(store.all.first().isEmpty())
    }

    @Test
    fun `a damaged record reads as empty and an unknown kind as a saved address`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit {
            it[stringPreferencesKey("server.s1")] = "{not json"
            it[stringPreferencesKey("server.s2")] = """{"candidates":[{"uri":"https://x","kind":"WORMHOLE"}],"future":1}"""
        }
        val store = DataStoreServerStatusStore(data)

        assertEquals(ServerStatusRecord(), store.get("s1"))
        assertEquals(PlexConnectionKind.SAVED, store.get("s2").candidates.single().toCandidate().kind)
    }
}
