package com.chapterplayer.data.merge

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.playback.MirrorPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MergedProgressPeersTest {

    private fun book(id: String, server: String, dur: Long, source: ServerSource = ServerSource.ABS) =
        AudioBook(id, "The Book", "Ann Author", "", dur, emptyList(), "", null, source, server)

    private val absCopy = book("abs_1", "s1", 36_000_000L)
    private val plexCopy = book("plex_1", "s2", 36_060_000L, ServerSource.PLEX)

    @Test
    fun `the other copies of a merged book are its peers, with their server and length`() = runTest {
        val peers = MergedProgressPeers(MutableStateFlow(listOf(absCopy, plexCopy)), MutableStateFlow(MergeOverrides()), MutableStateFlow(true), backgroundScope)
        assertEquals(listOf(MirrorPeer("plex_1", "s2", 36_060_000L)), peers.peersOf("abs_1"))
        assertEquals(listOf(MirrorPeer("abs_1", "s1", 36_000_000L)), peers.peersOf("plex_1"))
        assertTrue(peers.peersOf("unknown").isEmpty())
    }

    @Test
    fun `a split stops it at once and the setting off gives no peers`() = runTest {
        val overrides = MutableStateFlow(MergeOverrides())
        val enabled = MutableStateFlow(true)
        val peers = MergedProgressPeers(MutableStateFlow(listOf(absCopy, plexCopy)), overrides, enabled, backgroundScope)
        assertEquals(1, peers.peersOf("abs_1").size)

        overrides.value = MergeOverrides(splits = setOf("plex_1" to "abs_1"))
        assertTrue(peers.peersOf("abs_1").isEmpty())

        overrides.value = MergeOverrides()
        enabled.value = false
        assertTrue(peers.peersOf("abs_1").isEmpty())
    }

    @Test
    fun `several books are looked up in one go, and a correction or the switch is announced`() = runTest {
        val overrides = MutableStateFlow(MergeOverrides())
        val enabled = MutableStateFlow(true)
        val peers = MergedProgressPeers(MutableStateFlow(listOf(absCopy, plexCopy)), overrides, enabled, backgroundScope)
        val all = peers.peersOfAll(listOf("abs_1", "plex_1", "x"))!!
        assertEquals(listOf("plex_1"), all["abs_1"]?.map { it.bookId })
        assertEquals(listOf("abs_1"), all["plex_1"]?.map { it.bookId })
        assertTrue(all["x"].isNullOrEmpty())

        val announced = mutableListOf<Unit>()
        backgroundScope.launch { peers.changes.collect { announced += it } }
        runCurrent()
        assertTrue(announced.isEmpty()) // the current state is no change
        overrides.value = MergeOverrides(splits = setOf("abs_1" to "plex_1"))
        runCurrent()
        enabled.value = false
        runCurrent()
        assertEquals(2, announced.size)
        enabled.value = true
        assertTrue(peers.peersOfAll(listOf("abs_1"))!!.values.all { it.isEmpty() })
    }

    @Test
    fun `a lookup that fails says so instead of claiming no copies`() = runTest {
        val failing = kotlinx.coroutines.flow.flow<MergeOverrides> { throw IllegalStateException("db closed") }
        val peers = MergedProgressPeers(MutableStateFlow(listOf(absCopy, plexCopy)), failing, MutableStateFlow(true), backgroundScope)
        kotlin.test.assertNull(peers.peersOfAll(listOf("abs_1")))
        assertTrue(peers.peersOf("abs_1").isEmpty())
        // The switch off is a real "no copies".
        val off = MergedProgressPeers(MutableStateFlow(listOf(absCopy, plexCopy)), MutableStateFlow(MergeOverrides()), MutableStateFlow(false), backgroundScope)
        assertEquals(emptyList(), off.peersOfAll(listOf("abs_1"))?.get("abs_1").orEmpty())
        kotlin.test.assertNotNull(off.peersOfAll(listOf("abs_1")))
    }

    @Test
    fun `a book that joins the library is grouped on the next look`() = runTest {
        val books = MutableStateFlow(listOf(absCopy))
        val peers = MergedProgressPeers(books, MutableStateFlow(MergeOverrides()), MutableStateFlow(true), backgroundScope)
        assertTrue(peers.peersOf("abs_1").isEmpty())
        books.value = listOf(absCopy, plexCopy)
        runCurrent() // the cached library follows the table
        assertEquals(listOf("plex_1"), peers.peersOf("abs_1").map { it.bookId })
    }
}
