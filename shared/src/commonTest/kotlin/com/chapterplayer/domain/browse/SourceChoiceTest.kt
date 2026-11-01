package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceChoiceTest {

    private val hour = 3_600_000L

    private fun item(
        id: String,
        serverId: String,
        download: DownloadState = DownloadState.NOT_DOWNLOADED,
        lastPlayedAt: Long? = null,
    ) = BrowseItem(
        AudioBook(
            id = id, title = "Same Book", author = "Same Author", narrator = "", durationMs = 10 * hour,
            chapters = emptyList(), streamUrl = "", coverUrl = null,
            source = if (serverId.startsWith("plex")) ServerSource.PLEX else ServerSource.ABS, serverId = serverId,
        ),
        lastPlayedAt?.let { ReadingProgress(id, 1_000L, 10 * hour, it) },
        download,
    )

    private val everyServer = setOf("abs1", "abs2", "plex1", "plex2")

    // The entry as the app builds it: merged with the same play choice and servers the selection is asked with.
    private fun entry(vararg items: BrowseItem, online: Set<String> = everyServer, choice: SourceChoice = SourceChoice.Ask): BrowseItem =
        mergeDuplicates(items.toList(), MergeOverrides(), choice, online).single()

    // ---- stored form ----

    @Test
    fun `every choice is stored as one string and read back`() {
        val choices = listOf(
            SourceChoice.Ask, SourceChoice.OfType(ServerSource.ABS), SourceChoice.OfType(ServerSource.PLEX),
            SourceChoice.OfServer("server-1"),
        )
        assertEquals(listOf("ask", "type:ABS", "type:PLEX", "server:server-1"), choices.map { it.stored() })
        for (choice in choices) assertEquals(choice, parseSourceChoice(choice.stored()))
    }

    @Test
    fun `nothing stored or something unknown reads as ask`() {
        for (text in listOf(null, "", "type:", "type:AUDIBLE", "server:", "server:  ", "PREFER_ABS", "nonsense")) {
            assertEquals(SourceChoice.Ask, parseSourceChoice(text), "$text")
        }
    }

    @Test
    fun `the old single setting migrates to both choices`() {
        assertEquals(SourceChoice.Ask, migratedSourceChoice("ASK"))
        assertEquals(SourceChoice.OfType(ServerSource.ABS), migratedSourceChoice("PREFER_ABS"))
        assertEquals(SourceChoice.OfType(ServerSource.PLEX), migratedSourceChoice("PREFER_PLEX"))
        // The old "prefer the downloaded copy" never asked; asking is what Robert wants now.
        assertEquals(SourceChoice.Ask, migratedSourceChoice("PREFER_DOWNLOADED"))
        assertEquals(SourceChoice.Ask, migratedSourceChoice(null))
        assertEquals(SourceChoice.Ask, migratedSourceChoice("SOMETHING_NEW"))
    }

    @Test
    fun `a removed server's choice falls back to ask`() {
        assertEquals(SourceChoice.Ask, SourceChoice.OfServer("gone").resolvedAgainst(setOf("abs1")))
        assertEquals(SourceChoice.OfServer("abs1"), SourceChoice.OfServer("abs1").resolvedAgainst(setOf("abs1")))
        assertEquals(SourceChoice.OfType(ServerSource.PLEX), SourceChoice.OfType(ServerSource.PLEX).resolvedAgainst(emptySet()))
        assertEquals(SourceChoice.Ask, SourceChoice.Ask.resolvedAgainst(setOf("abs1")))
    }

    // ---- play ----

    @Test
    fun `a downloaded copy always plays first whatever the choice`() {
        val choices = listOf(SourceChoice.Ask, SourceChoice.OfType(ServerSource.ABS), SourceChoice.OfServer("abs1"))
        for (online in listOf(everyServer, emptySet())) {
                for (choice in choices) {
                val e = entry(item("a", "abs1"), item("p", "plex1", download = DownloadState.DOWNLOADED), online = online, choice = choice)
                assertEquals("p", chosenPlaySource(e, choice, online)?.book?.id, "$choice $online")
            }
        }
    }

    @Test
    fun `several downloaded copies play the one played last - else the first`() {
        val played = entry(
            item("a", "abs1", download = DownloadState.DOWNLOADED, lastPlayedAt = 5L),
            item("p", "plex1", download = DownloadState.DOWNLOADED, lastPlayedAt = 9L),
        )
        assertEquals("p", chosenPlaySource(played, SourceChoice.OfType(ServerSource.ABS), everyServer)?.book?.id)
        // Never played: the first in member order (both downloaded and reachable, so Audiobookshelf), whatever the input order.
        val never = entry(item("p", "plex1", download = DownloadState.DOWNLOADED), item("a", "abs1", download = DownloadState.DOWNLOADED))
        assertEquals("a", chosenPlaySource(never, SourceChoice.Ask, everyServer)?.book?.id)
        // With a play choice the choice's copy comes first among them.
        val chosen = entry(
            item("a", "abs1", download = DownloadState.DOWNLOADED), item("p", "plex1", download = DownloadState.DOWNLOADED),
            choice = SourceChoice.OfType(ServerSource.PLEX),
        )
        assertEquals("p", chosenPlaySource(chosen, SourceChoice.OfType(ServerSource.PLEX), everyServer)?.book?.id)
        // A mirrored row as new as the copy really played does not win.
        val mirrored = item("a", "abs1", download = DownloadState.DOWNLOADED, lastPlayedAt = 9L)
            .let { it.copy(progress = it.progress!!.copy(mirrored = true)) }
        val tie = entry(mirrored, item("p", "plex1", download = DownloadState.DOWNLOADED, lastPlayedAt = 9L))
        assertEquals("p", chosenPlaySource(tie, SourceChoice.Ask, everyServer)?.book?.id)
    }

    @Test
    fun `the play table - nothing downloaded`() {
        // Columns: the copies, the servers that answer, then the copy for each choice (null = ask).
        data class Row(
            val name: String, val items: List<BrowseItem>, val online: Set<String>,
            val ask: String?, val abs: String?, val plex: String?, val abs2: String?,
        )
        val absPlex = listOf(item("a", "abs1"), item("p", "plex1"))
        val twoAbs = listOf(item("a", "abs1"), item("b", "abs2"), item("p", "plex1"))
        val twoPlex = listOf(item("p", "plex1"), item("q", "plex2"))
        val table = listOf(
            Row("both reachable", absPlex, everyServer, ask = null, abs = "a", plex = "p", abs2 = null),
            Row("Plex unreachable", absPlex, setOf("abs1"), ask = null, abs = "a", plex = null, abs2 = null),
            Row("Audiobookshelf unreachable", absPlex, setOf("plex1"), ask = null, abs = null, plex = "p", abs2 = null),
            Row("offline", absPlex, emptySet(), ask = null, abs = null, plex = null, abs2 = null),
            Row("two Audiobookshelf servers", twoAbs, everyServer, ask = null, abs = "a", plex = "p", abs2 = "b"),
            Row("the chosen ABS server down", twoAbs, setOf("abs1", "plex1"), ask = null, abs = "a", plex = "p", abs2 = null),
            Row("Plex only", twoPlex, everyServer, ask = null, abs = null, plex = "p", abs2 = null),
        )
        for (row in table) {
            val expected = mapOf(
                SourceChoice.Ask to row.ask,
                SourceChoice.OfType(ServerSource.ABS) to row.abs,
                SourceChoice.OfType(ServerSource.PLEX) to row.plex,
                SourceChoice.OfServer("abs2") to row.abs2,
            )
            for ((choice, id) in expected) {
                val e = entry(*row.items.toTypedArray(), online = row.online, choice = choice)
                assertEquals(id, chosenPlaySource(e, choice, row.online)?.book?.id, "${row.name}: $choice")
            }
        }
    }

    @Test
    fun `a choice of one server plays that server's copy`() {
        for ((server, id) in listOf("plex2" to "q", "plex1" to "p")) {
            val choice = SourceChoice.OfServer(server)
            assertEquals(id, chosenPlaySource(entry(item("p", "plex1"), item("q", "plex2"), choice = choice), choice, everyServer)?.book?.id)
        }
        // A server the book is not on: ask.
        val elsewhere = SourceChoice.OfServer("abs1")
        assertNull(chosenPlaySource(entry(item("p", "plex1"), item("q", "plex2"), choice = elsewhere), elsewhere, everyServer))
    }

    @Test
    fun `a book on one server is always itself`() {
        val single = item("a", "abs1")
        for (choice in listOf(SourceChoice.Ask, SourceChoice.OfType(ServerSource.PLEX), SourceChoice.OfServer("x"))) {
            assertEquals(single, chosenPlaySource(single, choice, emptySet()))
            assertEquals(single, chosenDownloadSource(single, choice, emptySet()))
        }
    }

    // ---- download ----

    @Test
    fun `the download table`() {
        data class Row(
            val name: String, val items: List<BrowseItem>, val online: Set<String>,
            val ask: String?, val abs: String?, val plex: String?, val abs2: String?,
        )
        val absPlex = listOf(item("a", "abs1"), item("p", "plex1"))
        val twoAbs = listOf(item("a", "abs1"), item("b", "abs2"), item("p", "plex1"))
        val table = listOf(
            Row("both reachable", absPlex, everyServer, ask = null, abs = "a", plex = "p", abs2 = null),
            Row("Plex unreachable", absPlex, setOf("abs1"), ask = null, abs = "a", plex = null, abs2 = null),
            Row("offline", absPlex, emptySet(), ask = null, abs = null, plex = null, abs2 = null),
            Row("two Audiobookshelf servers", twoAbs, everyServer, ask = null, abs = "a", plex = "p", abs2 = "b"),
            Row("the chosen ABS server down", twoAbs, setOf("abs1", "plex1"), ask = null, abs = "a", plex = "p", abs2 = null),
            // The chosen type's copy is on the phone already: nothing to fetch without asking.
            Row("ABS downloaded", listOf(item("a", "abs1", download = DownloadState.DOWNLOADED), item("p", "plex1")), everyServer,
                ask = null, abs = null, plex = "p", abs2 = null),
            Row("ABS downloading", listOf(item("a", "abs1", download = DownloadState.DOWNLOADING), item("p", "plex1")), everyServer,
                ask = null, abs = null, plex = "p", abs2 = null),
        )
        for (row in table) {
            val expected = mapOf(
                SourceChoice.Ask to row.ask,
                SourceChoice.OfType(ServerSource.ABS) to row.abs,
                SourceChoice.OfType(ServerSource.PLEX) to row.plex,
                SourceChoice.OfServer("abs2") to row.abs2,
            )
            // The list is merged with the play choice (Ask here); the download choice only picks a member.
            val e = entry(*row.items.toTypedArray(), online = row.online)
            for ((choice, id) in expected) assertEquals(id, chosenDownloadSource(e, choice, row.online)?.book?.id, "${row.name}: $choice")
        }
    }

    // ---- primary copy ----

    @Test
    fun `the primary is downloaded first - then the reachable chosen copy - then reachable - then the choice - then Audiobookshelf`() {
        val plexChoice = SourceChoice.OfType(ServerSource.PLEX)
        fun primary(items: List<BrowseItem>, choice: SourceChoice, online: Set<String> = everyServer) =
            mergeDuplicates(items, MergeOverrides(), choice, online).single().book.id
        val pair = listOf(item("a", "abs1"), item("p", "plex1"))
        assertEquals("p", primary(pair, plexChoice))
        assertEquals("a", primary(pair, SourceChoice.Ask))
        assertEquals("a", primary(pair, SourceChoice.OfType(ServerSource.ABS)))
        assertEquals("p", primary(pair, SourceChoice.Ask, online = setOf("plex1")))
        assertEquals("a", primary(listOf(item("a", "abs1", download = DownloadState.DOWNLOADED), item("p", "plex1")), plexChoice))
        val twoAbs = listOf(item("a", "abs1"), item("b", "abs2"))
        assertEquals("b", primary(twoAbs, SourceChoice.OfServer("abs2")))
        assertEquals("a", primary(twoAbs, SourceChoice.Ask))
    }

    @Test
    fun `a chosen copy whose server does not answer gives way to a reachable one`() {
        fun primary(items: List<BrowseItem>, choice: SourceChoice, online: Set<String>) =
            mergeDuplicates(items, MergeOverrides(), choice, online).single().book.id
        val pair = listOf(item("a", "abs1"), item("p", "plex1"))
        assertEquals("p", primary(pair, SourceChoice.OfType(ServerSource.ABS), setOf("plex1")))
        assertEquals("a", primary(pair, SourceChoice.OfType(ServerSource.PLEX), setOf("abs1")))
        // Nothing answers: the chosen copy still comes first.
        assertEquals("p", primary(pair, SourceChoice.OfType(ServerSource.PLEX), emptySet()))
        // Of two unreachable copies behind a reachable one, the chosen one is next.
        val three = listOf(item("a", "abs1"), item("b", "abs2"), item("p", "plex1"))
        val entry = mergeDuplicates(three, MergeOverrides(), SourceChoice.OfServer("abs2"), setOf("plex1")).single()
        assertEquals(listOf("p", "b", "a"), entry.sources.map { it.book.id })
    }

    @Test
    fun `resume takes a downloaded copy over streaming the copy played last`() {
        val e = mergeDuplicates(
            listOf(item("a", "abs1", lastPlayedAt = 9L), item("p", "plex1", download = DownloadState.DOWNLOADED, lastPlayedAt = 2L)),
            MergeOverrides(), SourceChoice.Ask, everyServer,
        ).single()
        assertEquals("a", e.progress?.bookId)
        assertEquals("p", resumeCopy(e).book.id)
        // A downloaded copy that cannot play now (its files are gone) does not count.
        assertEquals("a", resumeCopy(e) { it.book.id != "p" }.book.id)
    }

    @Test
    fun `resume falls back by the same rule when the copy played last cannot play`() {
        val e = mergeDuplicates(
            listOf(item("a", "abs1", lastPlayedAt = 9L), item("p", "plex1"), item("q", "plex2", download = DownloadState.DOWNLOADED)),
            MergeOverrides(), SourceChoice.OfServer("plex1"), everyServer,
        ).single()
        // A downloaded copy that can play is resumed rather than streaming the copy played last.
        assertEquals("q", resumeCopy(e).book.id)
        // The copy played last cannot play: the downloaded one, then the chosen one.
        assertEquals("q", resumeCopy(e) { it.book.id != "a" }.book.id)
        assertEquals("p", resumeCopy(e) { it.book.id == "p" || it.book.id == "b" }.book.id)
    }
}
