package com.chapterplayer.data.merge

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.playback.MirrorPeer
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun `a book that joins the library is grouped on the next look`() = runTest {
        val books = MutableStateFlow(listOf(absCopy))
        val peers = MergedProgressPeers(books, MutableStateFlow(MergeOverrides()), MutableStateFlow(true), backgroundScope)
        assertTrue(peers.peersOf("abs_1").isEmpty())
        books.value = listOf(absCopy, plexCopy)
        runCurrent() // the cached library follows the table
        assertEquals(listOf("plex_1"), peers.peersOf("abs_1").map { it.bookId })
    }
}
