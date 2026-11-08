package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergedSourcesTest {

    private val hour = 3_600_000L

    private fun item(
        id: String,
        serverId: String,
        title: String = "The Way of Kings",
        author: String = "Brandon Sanderson",
        durationMs: Long = 45 * hour,
        source: ServerSource = if (serverId.startsWith("plex")) ServerSource.PLEX else ServerSource.ABS,
        download: DownloadState = DownloadState.NOT_DOWNLOADED,
        lastPlayedAt: Long? = null,
    ) = BrowseItem(
        AudioBook(
            id = id, title = title, author = author, narrator = "", durationMs = durationMs,
            chapters = emptyList(), streamUrl = "", coverUrl = null, source = source, serverId = serverId,
        ),
        lastPlayedAt?.let { ReadingProgress(id, 1_000L, durationMs, it) },
        download,
    )

    private val everyServer = setOf("abs1", "abs2", "plex1", "plex2")

    private fun merge(
        items: List<BrowseItem>,
        overrides: MergeOverrides = MergeOverrides(),
        choice: SourceChoice = SourceChoice.Ask,
        online: Set<String> = everyServer,
    ) = mergeDuplicates(items, overrides, choice, online)

    private fun groups(result: List<BrowseItem>): Set<Set<String>> =
        result.map { entry -> entry.allSources.map { it.book.id }.toSet() }.toSet()

    // ---- matching ----

    @Test
    fun `the same book on Audiobookshelf and Plex is one entry`() {
        val result = merge(listOf(item("a", "abs1"), item("p", "plex1")))
        assertEquals(1, result.size)
        assertTrue(result.single().isMerged)
        assertEquals(listOf("a", "p"), result.single().allSources.map { it.book.id })
    }

    @Test
    fun `the same book on Audiobookshelf Plex and Jellyfin is one entry`() {
        val result = merge(
            listOf(
                item("a", "abs1", durationMs = 10 * hour),
                item("p", "plex1", durationMs = 10 * hour),
                item("jf_x", "jf1", durationMs = 10 * hour - 4_000L, source = ServerSource.JELLYFIN),
            ),
            online = everyServer + "jf1",
        )
        assertEquals(1, result.size)
        assertEquals(3, result.single().sources.size)
        assertEquals(setOf("a", "p", "jf_x"), result.single().allSources.map { it.book.id }.toSet())
    }

    @Test
    fun `case punctuation articles and diacritics do not matter`() {
        val result = merge(listOf(
            item("a", "abs1", title = "The Way of Kings", author = "Brandon Sanderson"),
            item("p", "plex1", title = "way of kings!", author = "BRANDON SANDERSON"),
        ))
        assertEquals(1, result.size)
        val accents = merge(listOf(
            item("a", "abs1", title = "Les Misérables", author = "Victor Hugo"),
            item("p", "plex1", title = "Les Miserables", author = "Víctor Hugo"),
        ))
        assertEquals(1, accents.size)
        val initials = merge(listOf(
            item("a", "abs1", title = "The Hobbit", author = "J.R.R. Tolkien"),
            item("p", "plex1", title = "Hobbit", author = "J. R. R. Tolkien"),
        ))
        assertEquals(1, initials.size)
    }

    @Test
    fun `unabridged and abridged suffixes are ignored`() {
        listOf("The Way of Kings (Unabridged)", "The Way of Kings [Unabridged]", "The Way of Kings - Unabridged", "The Way of Kings (Abridged)")
            .forEach { title ->
                val result = merge(listOf(item("a", "abs1"), item("p", "plex1", title = title)))
                assertEquals(1, result.size, title)
            }
    }

    @Test
    fun `only the first author has to agree`() {
        val result = merge(listOf(
            item("a", "abs1", author = "Brandon Sanderson, Someone Else"),
            item("p", "plex1", author = "Brandon Sanderson & Another"),
        ))
        assertEquals(1, result.size)
    }

    @Test
    fun `different titles or authors stay apart`() {
        assertEquals(2, merge(listOf(item("a", "abs1"), item("p", "plex1", title = "Words of Radiance"))).size)
        assertEquals(2, merge(listOf(item("a", "abs1"), item("p", "plex1", author = "Someone Else"))).size)
        assertEquals(2, merge(listOf(item("a", "abs1", title = "Kings"), item("p", "plex1", title = "The Kings Way"))).size)
    }

    @Test
    fun `lengths within two percent or five minutes merge - others do not`() {
        val base = 45 * hour
        // 2% of 45 h is 54 minutes.
        assertEquals(1, merge(listOf(item("a", "abs1", durationMs = base), item("p", "plex1", durationMs = base + 50 * 60_000L))).size)
        assertEquals(2, merge(listOf(item("a", "abs1", durationMs = base), item("p", "plex1", durationMs = base + 60 * 60_000L))).size)
        // A short book: 5 minutes is more than 2%.
        assertEquals(1, merge(listOf(item("a", "abs1", durationMs = hour), item("p", "plex1", durationMs = hour + 4 * 60_000L))).size)
        assertEquals(2, merge(listOf(item("a", "abs1", durationMs = hour), item("p", "plex1", durationMs = hour + 6 * 60_000L))).size)
    }

    @Test
    fun `an unknown length merges only when every author agrees`() {
        // The Plex album list has no length until the book is opened: the real case, same title and same authors.
        val abs = item("a", "abs1", title = "The Space Within Season 2", author = "Ann Writer, Bob Writer", durationMs = 17_931_321L)
        val plex = item("p", "plex1", title = "The Space Within Season 2", author = "Ann Writer, Bob Writer", durationMs = 0)
        assertEquals(1, merge(listOf(abs, plex)).size)
        // The same names written another way still agree.
        assertEquals(1, merge(listOf(abs, plex.copy(book = plex.book.copy(author = "ann writer & Bob Writer")))).size)
        // Only the first author agrees, one author more, or none: apart.
        assertEquals(2, merge(listOf(abs, plex.copy(book = plex.book.copy(author = "Ann Writer, Cy Writer")))).size)
        assertEquals(2, merge(listOf(abs, plex.copy(book = plex.book.copy(author = "Ann Writer, Bob Writer, Cy Writer")))).size)
        assertEquals(2, merge(listOf(abs, plex.copy(book = plex.book.copy(author = "Ann Writer")))).size)
        // Both unknown: the same rule.
        assertEquals(1, merge(listOf(abs.copy(book = abs.book.copy(durationMs = 0)), plex)).size)
        assertEquals(2, merge(listOf(abs.copy(book = abs.book.copy(durationMs = 0, author = "Ann Writer")), plex)).size)
        // Never two books of one server.
        assertEquals(2, merge(listOf(abs, plex.copy(book = plex.book.copy(serverId = "abs1", source = ServerSource.ABS)))).size)
    }

    @Test
    fun `abridged and unabridged copies with known lengths stay apart`() {
        val unabridged = item("a", "abs1", title = "The Way of Kings (Unabridged)", durationMs = 45 * hour)
        val abridged = item("p", "plex1", title = "The Way of Kings (Abridged)", durationMs = 12 * hour)
        assertEquals(2, merge(listOf(unabridged, abridged)).size)
    }

    @Test
    fun `a copy with a matching length is preferred over one with an unknown length`() {
        val result = merge(listOf(item("a", "abs1"), item("p0", "plex1", durationMs = 0), item("p1", "plex1")))
        assertEquals(setOf(setOf("a", "p1"), setOf("p0")), groups(result))
    }

    @Test
    fun `a merged book shows the known length when its primary has none`() {
        val entry = merge(listOf(item("a", "abs1"), item("p", "plex1", durationMs = 0)), choice = SourceChoice.OfType(ServerSource.PLEX)).single()
        assertEquals("p", entry.book.id)
        assertEquals(45 * hour, entry.book.durationMs)
        assertEquals(0L, entry.sources.first().book.durationMs)
    }

    @Test
    fun `books on the same server never merge`() {
        assertEquals(2, merge(listOf(item("a", "abs1"), item("b", "abs1"))).size)
    }

    @Test
    fun `two Audiobookshelf servers merge too`() {
        assertEquals(1, merge(listOf(item("a", "abs1"), item("b", "abs2"))).size)
    }

    @Test
    fun `groups are transitive with at most one book per server`() {
        val result = merge(listOf(item("a", "abs1"), item("p", "plex1"), item("q", "plex2")))
        assertEquals(setOf(setOf("a", "p", "q")), groups(result))
        // Two copies on plex1: only one of them can join.
        val crowded = merge(listOf(item("a", "abs1"), item("p", "plex1"), item("p2", "plex1")))
        assertEquals(2, crowded.size)
        assertTrue(crowded.all { entry -> entry.allSources.map { it.book.serverId }.toSet().size == entry.allSources.size })
    }

    @Test
    fun `the result does not depend on the input order`() {
        val list = listOf(item("a", "abs1"), item("p", "plex1"), item("p2", "plex1"), item("q", "plex2"), item("x", "abs2", title = "Other"))
        val expected = groups(merge(list))
        for (shuffled in listOf(list.reversed(), list.drop(2) + list.take(2), listOf(list[3], list[1], list[4], list[0], list[2]))) {
            assertEquals(expected, groups(merge(shuffled)))
        }
    }

    @Test
    fun `entries keep the input order and unmatched books pass through untouched`() {
        val other = item("x", "abs1", title = "Other")
        val result = merge(listOf(other, item("a", "abs1"), item("p", "plex1")))
        assertEquals(listOf("x", "a"), result.map { it.book.id })
        assertEquals(other, result[0])
    }

    // ---- overrides ----

    @Test
    fun `a split keeps two matching books apart`() {
        val result = merge(listOf(item("a", "abs1"), item("p", "plex1")), MergeOverrides(splits = setOf("p" to "a")))
        assertEquals(2, result.size)
    }

    @Test
    fun `a split also stops a group joining through a third member`() {
        val result = merge(
            listOf(item("a", "abs1"), item("p", "plex1"), item("q", "plex2")),
            MergeOverrides(splits = setOf("a" to "q", "p" to "q")),
        )
        assertEquals(setOf(setOf("a", "p"), setOf("q")), groups(result))
    }

    @Test
    fun `a manual merge joins books whose titles differ`() {
        val result = merge(
            listOf(item("a", "abs1"), item("p", "plex1", title = "Stormlight 1", durationMs = 0)),
            MergeOverrides(merges = setOf("a" to "p")),
        )
        assertEquals(setOf(setOf("a", "p")), groups(result))
    }

    @Test
    fun `a manual merge never puts two books of one server together`() {
        val result = merge(listOf(item("a", "abs1"), item("b", "abs1")), MergeOverrides(merges = setOf("a" to "b")))
        assertEquals(2, result.size)
    }

    @Test
    fun `a member that disappeared just shrinks the group`() {
        val overrides = MergeOverrides(merges = setOf("a" to "gone"))
        val result = merge(listOf(item("a", "abs1")), overrides)
        assertEquals(1, result.size)
        assertFalse(result.single().isMerged)
    }

    // ---- primary choice ----

    @Test
    fun `the primary is the copy the play choice plays - the member order when nothing decides`() {
        // Rows: which copy is downloaded, which servers answer; then the primary for Ask, Audiobookshelf, Plex.
        data class Row(val downloaded: String?, val online: Set<String>, val ask: String, val abs: String, val plex: String)
        val table = listOf(
            // A downloaded copy always comes first.
            Row("p", setOf("abs1", "plex1"), ask = "p", abs = "p", plex = "p"),
            Row(null, setOf("abs1", "plex1"), ask = "a", abs = "a", plex = "p"),
            Row("a", emptySet(), ask = "a", abs = "a", plex = "a"),
            // A reachable copy comes before the chosen type's copy that cannot play now.
            Row(null, setOf("plex1"), ask = "p", abs = "p", plex = "p"),
            Row(null, emptySet(), ask = "a", abs = "a", plex = "p"),
        )
        for (row in table) {
            val items = listOf(
                item("a", "abs1", download = if (row.downloaded == "a") DownloadState.DOWNLOADED else DownloadState.NOT_DOWNLOADED),
                item("p", "plex1", download = if (row.downloaded == "p") DownloadState.DOWNLOADED else DownloadState.NOT_DOWNLOADED),
            )
            val expected = mapOf(
                SourceChoice.Ask to row.ask,
                SourceChoice.OfType(ServerSource.ABS) to row.abs,
                SourceChoice.OfType(ServerSource.PLEX) to row.plex,
            )
            for ((choice, id) in expected) {
                val entry = merge(items, choice = choice, online = row.online).single()
                assertEquals(id, entry.book.id, "$choice $row")
                // The lists and the details page agree: when the choice decides, the primary is its copy.
                chosenPlaySource(entry, choice, row.online)?.let { assertEquals(it.book.id, entry.book.id, "$choice $row") }
            }
        }
    }

    @Test
    fun `a reachable server comes before an unreachable one`() {
        val result = merge(listOf(item("a", "abs1"), item("p", "plex1")), online = setOf("plex1"))
        assertEquals("p", result.single().book.id)
    }

    @Test
    fun `then the play choice decides - and Audiobookshelf otherwise`() {
        val pair = listOf(item("p", "plex1"), item("a", "abs1"))
        assertEquals("p", merge(pair, choice = SourceChoice.OfType(ServerSource.PLEX)).single().book.id)
        assertEquals("a", merge(pair, choice = SourceChoice.OfType(ServerSource.ABS)).single().book.id)
        assertEquals("p", merge(pair, choice = SourceChoice.OfServer("plex1")).single().book.id)
        assertEquals("a", merge(pair, choice = SourceChoice.Ask).single().book.id)
    }

    @Test
    fun `downloaded Audiobookshelf offline beats Plex`() {
        val result = merge(
            listOf(item("p", "plex1"), item("a", "abs1", download = DownloadState.DOWNLOADED)),
            choice = SourceChoice.OfType(ServerSource.PLEX),
            online = emptySet(),
        )
        assertEquals("a", result.single().book.id)
    }

    // ---- progress ----

    @Test
    fun `a merged entry shows the progress played most recently`() {
        val result = merge(listOf(item("a", "abs1", lastPlayedAt = 100L), item("p", "plex1", lastPlayedAt = 200L)))
        assertEquals("a", result.single().book.id)
        assertEquals("p", result.single().progress?.bookId)
        assertEquals(200L, result.single().progress?.lastPlayedAt)
        // The members keep their own progress.
        assertEquals(100L, result.single().sources.first { it.book.id == "a" }.progress?.lastPlayedAt)
    }

    @Test
    fun `progress from one member only still shows`() {
        val result = merge(listOf(item("a", "abs1"), item("p", "plex1", lastPlayedAt = 5L)))
        assertEquals(5L, result.single().progress?.lastPlayedAt)
    }

    // ---- the source sheet (the choices themselves: SourceChoiceTest) ----

    @Test
    fun `source options list downloaded first and say why one cannot play`() {
        val entry = merge(listOf(item("a", "abs1"), item("p", "plex1", download = DownloadState.DOWNLOADED))).single()
        val options = sourceOptions(entry, onlineServerIds = setOf("plex1"), deviceOnline = true)
        assertEquals(listOf("p", "a"), options.map { it.item.book.id })
        assertTrue(options[0].playable)
        assertFalse(options[1].playable)
        assertEquals("Server not reachable", options[1].unavailableReason)
        val offline = sourceOptions(entry, onlineServerIds = emptySet(), deviceOnline = false)
        assertEquals("No network", offline[1].unavailableReason)
        assertTrue(offline[0].playable)
    }

    // ---- server filter ----

    @Test
    fun `a server filter keeps a merged book and shows the matching member`() {
        val items = merge(listOf(item("a", "abs1"), item("p", "plex1"), item("x", "abs1", title = "Other")))
        val shown = applyQuery(items, BrowseQuery(serverIds = setOf("plex1")))
        assertEquals(listOf("p"), shown.map { it.book.id })
        assertTrue(shown.single().isMerged)
        assertEquals("p", shown.single().allSources.first().book.id)
    }

    @Test
    fun `server counts count a merged book on each of its servers`() {
        val servers = listOf(
            com.chapterplayer.data.model.ServerConfig("abs1", ServerSource.ABS, "A", "http://a", "t"),
            com.chapterplayer.data.model.ServerConfig("plex1", ServerSource.PLEX, "P", "http://p", "t"),
        )
        val items = merge(listOf(item("a", "abs1"), item("p", "plex1")))
        assertEquals(listOf(1, 1), serverFilterOptions(servers, items, emptySet()).map { it.count })
    }

    @Test
    fun `a merged book is in a collection of any of its servers`() {
        val entry = merge(listOf(item("a", "abs1"), item("p", "plex1"))).single()
        val collection = com.chapterplayer.data.model.BookCollection("plex1", "c1", "Plex picks", listOf("p"))
        val groups = groupByCollection(listOf(entry), listOf(collection))
        assertEquals(listOf("a"), groups.single().items.map { it.book.id })
    }

    @Test
    fun `search matches any member's details`() {
        val entry = merge(listOf(item("a", "abs1", author = "Brandon Sanderson"), item("p", "plex1", author = "Brandon Sanderson; Kate Reading"))).single()
        assertEquals(1, searchItems(listOf(entry), "kate reading").size)
    }

    // ---- labels ----

    @Test
    fun `the mark and the spoken label name the sources`() {
        val entry = merge(listOf(item("a", "abs1"), item("p", "plex1"))).single()
        assertEquals("2 sources", sourcesMark(entry))
        assertEquals("available from Audiobookshelf and Plex", sourcesDescription(entry))
        val two = merge(listOf(item("a", "abs1"), item("b", "abs2"))).single()
        assertEquals("available from 2 Audiobookshelf servers", sourcesDescription(two))
        assertNull(sourcesMark(item("a", "abs1")))
    }

    @Test
    fun `normalised titles and authors`() {
        assertEquals(normalisedTitle("The Hobbit (Unabridged)"), normalisedTitle("hobbit"))
        assertEquals("", normalisedTitle("  "))
        assertEquals(normalisedFirstAuthor("Tolkien, J.R.R."), normalisedFirstAuthor("tolkien"))
    }
    // ---- details helpers ----

    @Test
    fun `the entry of any member is found`() {
        val all = merge(listOf(item("a", "abs1"), item("p", "plex1"), item("x", "abs1", title = "Other")))
        assertEquals("a", entryContaining(all, "p")?.book?.id)
        assertEquals("x", entryContaining(all, "x")?.book?.id)
        assertNull(entryContaining(all, "nope"))
    }

    @Test
    fun `merge candidates leave out the book itself and books on its servers`() {
        val all = merge(listOf(
            item("a", "abs1"),
            item("p", "plex1"),
            item("x", "abs1", title = "Other"),
            item("y", "plex2", title = "Stormlight One"),
            item("z", "abs2", title = "Another"),
        ))
        val current = entryContaining(all, "a")!!
        assertEquals(listOf("z", "y"), mergeCandidates(all, current, "").map { it.book.id })
        assertEquals(listOf("y"), mergeCandidates(all, current, "storm").map { it.book.id })
    }
    // ---- resume copy and grouping metadata ----

    @Test
    fun `a mirrored row as new as the copy played last does not take its place`() {
        // The Plex copy was listened to; the Audiobookshelf row was copied from it with the same time.
        val abs = item("a", "abs1", lastPlayedAt = 9L).let { it.copy(progress = it.progress!!.copy(mirrored = true)) }
        val entry = merge(listOf(abs, item("p", "plex1", lastPlayedAt = 9L))).single()
        assertEquals("p", entry.progress?.bookId)
        assertEquals("p", resumeCopy(entry).book.id)
    }

    @Test
    fun `resume picks the copy played last - or one that can play`() {
        val entry = merge(listOf(item("a", "abs1", lastPlayedAt = 1L), item("p", "plex1", lastPlayedAt = 9L))).single()
        assertEquals("a", entry.book.id)
        assertEquals("p", resumeCopy(entry).book.id)
        assertEquals("a", resumeCopy(entry) { it.book.id == "a" }.book.id)
        val info = resumeCandidate(listOf(entry))!!
        assertEquals("p", info.item.book.id)
        assertEquals("a", info.entry.book.id)
    }

    @Test
    fun `a Plex primary keeps the series - author and narrator of the Audiobookshelf copy`() {
        val absCopy = item("a", "abs1").let { it.copy(book = it.book.copy(seriesName = "Stormlight", seriesSequence = "1", narrator = "Kate Reading")) }
        val plexCopy = item("p", "plex1")
        val other = item("b", "abs1", title = "Words of Radiance").let { it.copy(book = it.book.copy(seriesName = "Stormlight", seriesSequence = "2")) }
        val all = merge(listOf(absCopy, plexCopy, other), choice = SourceChoice.OfType(ServerSource.PLEX))
        val entry = all.first { it.isMerged }
        assertEquals("p", entry.book.id)
        assertEquals("Stormlight", entry.book.seriesName)
        assertEquals("1", entry.book.seriesSequence)
        assertEquals("Kate Reading", entry.book.narrator)
        assertEquals("abs1", entry.seriesServerId)
        assertEquals(listOf("p", "b"), groupBySeries(all).single().items.map { it.book.id })
        assertEquals(listOf("p"), groupByNarrator(all).first { it.title == "Kate Reading" }.items.map { it.book.id })
        // Continue series: the first book finished, so the second is next, on the same server as the series copy.
        val finished = all.map { if (it.isMerged) it.copy(progress = com.chapterplayer.data.model.ReadingProgress("p", 1L, 1L, 5L, isFinished = true)) else it }
        assertEquals(listOf("b"), continueSeries(finished).map { it.book.id })
    }

    @Test
    fun `after the splits between two groups are gone they join - three servers`() {
        // a (abs1) and p (plex1) are one book; q (plex2) was split from both, then "Merge with" forgot those splits.
        val items = listOf(item("a", "abs1"), item("p", "plex1"), item("q", "plex2"))
        val before = merge(items, MergeOverrides(splits = setOf("a" to "q", "p" to "q")))
        assertEquals(setOf(setOf("a", "p"), setOf("q")), groups(before))
        val current = entryContaining(before, "q")!!
        assertEquals(listOf("a"), mergeCandidates(before, current, "").map { it.book.id })
        val after = merge(items, MergeOverrides(merges = setOf("a" to "q")))
        assertEquals(setOf(setOf("a", "p", "q")), groups(after))
    }

    // ---- three kinds ----

    private val withJf = everyServer + "jf1"

    private fun jf(id: String, durationMs: Long = 45 * hour) = item(id, "jf1", durationMs = durationMs, source = ServerSource.JELLYFIN)

    @Test
    fun `abs plex and jellyfin copies make one entry`() {
        val result = merge(
            listOf(item("a", "abs1", durationMs = 10 * hour), item("p", "plex1", durationMs = 10 * hour), jf("j", 10 * hour - 4_000L)),
            online = withJf,
        )
        assertEquals(1, result.size)
        assertEquals(3, result.single().sources.size)
    }

    @Test
    fun `on a tie the order is Audiobookshelf then Jellyfin then Plex`() {
        val result = merge(listOf(item("p", "plex1"), jf("j"), item("a", "abs1")), online = withJf)
        assertEquals(listOf("a", "j", "p"), result.single().allSources.map { it.book.id })
    }

    @Test
    fun `jellyfin only plus plex makes Jellyfin the primary on a tie`() {
        val result = merge(listOf(item("p", "plex1"), jf("j")), online = withJf)
        assertEquals("j", result.single().book.id)
        assertEquals(listOf("j", "p"), result.single().allSources.map { it.book.id })
    }

    @Test
    fun `narrator and series come from Jellyfin before Plex when Audiobookshelf has none`() {
        fun with(item: BrowseItem, narrator: String, series: String?) = item.copy(book = item.book.copy(narrator = narrator, seriesName = series))
        val result = merge(
            listOf(
                item("a", "abs1"),
                with(item("p", "plex1"), "Plex Voice", "Plex Series"),
                with(jf("j"), "Jellyfin Voice", "Jellyfin Series"),
            ),
            online = withJf,
        )
        val book = result.single().book
        assertEquals("Jellyfin Voice", book.narrator)
        assertEquals("Jellyfin Series", book.seriesName)
        assertEquals("jf1", result.single().seriesServerId)
    }

    @Test
    fun `a Jellyfin copy is never picked to download`() {
        val entry = merge(listOf(item("a", "abs1"), jf("j")), online = withJf).single()
        assertNull(chosenDownloadSource(entry, SourceChoice.OfType(ServerSource.JELLYFIN), withJf))
        assertNull(chosenDownloadSource(entry, SourceChoice.OfServer("jf1"), withJf))
        assertEquals("a", chosenDownloadSource(entry, SourceChoice.OfType(ServerSource.ABS), withJf)?.book?.id)
        // A Jellyfin book on its own cannot be downloaded at all (until J3).
        assertNull(chosenDownloadSource(jf("solo"), SourceChoice.Ask, withJf))
        assertFalse(ServerSource.JELLYFIN.canDownload())
        assertTrue(ServerSource.ABS.canDownload() && ServerSource.PLEX.canDownload())
    }
}
