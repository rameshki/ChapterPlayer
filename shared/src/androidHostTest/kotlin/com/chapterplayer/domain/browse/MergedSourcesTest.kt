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
        preference: SourcePreference = SourcePreference.PREFER_DOWNLOADED,
        online: Set<String> = everyServer,
    ) = mergeDuplicates(items, overrides, preference, online)

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
    fun `lengths within two percent or five minutes merge, others do not`() {
        val base = 45 * hour
        // 2% of 45 h is 54 minutes.
        assertEquals(1, merge(listOf(item("a", "abs1", durationMs = base), item("p", "plex1", durationMs = base + 50 * 60_000L))).size)
        assertEquals(2, merge(listOf(item("a", "abs1", durationMs = base), item("p", "plex1", durationMs = base + 60 * 60_000L))).size)
        // A short book: 5 minutes is more than 2%.
        assertEquals(1, merge(listOf(item("a", "abs1", durationMs = hour), item("p", "plex1", durationMs = hour + 4 * 60_000L))).size)
        assertEquals(2, merge(listOf(item("a", "abs1", durationMs = hour), item("p", "plex1", durationMs = hour + 6 * 60_000L))).size)
    }

    @Test
    fun `an unknown length never merges automatically`() {
        assertEquals(2, merge(listOf(item("a", "abs1", durationMs = 0), item("p", "plex1", durationMs = 0))).size)
        assertEquals(2, merge(listOf(item("a", "abs1"), item("p", "plex1", durationMs = 0))).size)
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
    fun `the downloaded copy is the primary whatever the preference`() {
        for (preference in SourcePreference.entries) {
            val result = merge(listOf(item("a", "abs1"), item("p", "plex1", download = DownloadState.DOWNLOADED)), preference = preference)
            assertEquals("p", result.single().book.id, preference.name)
            assertEquals(DownloadState.DOWNLOADED, result.single().downloadState)
        }
    }

    @Test
    fun `a reachable server comes before an unreachable one`() {
        val result = merge(listOf(item("a", "abs1"), item("p", "plex1")), online = setOf("plex1"))
        assertEquals("p", result.single().book.id)
    }

    @Test
    fun `then the preference decides, and Audiobookshelf otherwise`() {
        val pair = listOf(item("p", "plex1"), item("a", "abs1"))
        assertEquals("p", merge(pair, preference = SourcePreference.PREFER_PLEX).single().book.id)
        assertEquals("a", merge(pair, preference = SourcePreference.PREFER_ABS).single().book.id)
        assertEquals("a", merge(pair, preference = SourcePreference.PREFER_DOWNLOADED).single().book.id)
        assertEquals("a", merge(pair, preference = SourcePreference.ASK).single().book.id)
    }

    @Test
    fun `downloaded Audiobookshelf offline beats reachable Plex`() {
        val result = merge(
            listOf(item("p", "plex1"), item("a", "abs1", download = DownloadState.DOWNLOADED)),
            preference = SourcePreference.PREFER_PLEX,
            online = setOf("plex1"),
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

    // ---- choosing a source to play ----

    @Test
    fun `the default prefers the downloaded copy, then Audiobookshelf, then asks`() {
        val downloaded = merge(listOf(item("a", "abs1"), item("p", "plex1", download = DownloadState.DOWNLOADED))).single()
        assertEquals("p", chosenSource(downloaded, SourcePreference.PREFER_DOWNLOADED, everyServer)?.book?.id)
        val neither = merge(listOf(item("a", "abs1"), item("p", "plex1"))).single()
        assertEquals("a", chosenSource(neither, SourcePreference.PREFER_DOWNLOADED, everyServer)?.book?.id)
        // Only Plex reachable: the one copy that can play.
        assertEquals("p", chosenSource(neither, SourcePreference.PREFER_DOWNLOADED, setOf("plex1"))?.book?.id)
        // Nothing can play: ask (the sheet says why).
        assertNull(chosenSource(neither, SourcePreference.PREFER_DOWNLOADED, emptySet()))
        // Two Plex copies, no Audiobookshelf: ask.
        val twoPlex = merge(listOf(item("p", "plex1"), item("q", "plex2"))).single()
        assertNull(chosenSource(twoPlex, SourcePreference.PREFER_DOWNLOADED, everyServer))
    }

    @Test
    fun `prefer a server type uses it when it can play, otherwise asks`() {
        val entry = merge(listOf(item("a", "abs1"), item("p", "plex1"))).single()
        assertEquals("p", chosenSource(entry, SourcePreference.PREFER_PLEX, everyServer)?.book?.id)
        assertEquals("a", chosenSource(entry, SourcePreference.PREFER_ABS, everyServer)?.book?.id)
        // Plex cannot play but Audiobookshelf can: the one copy that can play.
        assertEquals("a", chosenSource(entry, SourcePreference.PREFER_PLEX, setOf("abs1"))?.book?.id)
        val three = merge(listOf(item("a", "abs1"), item("b", "abs2"), item("p", "plex1"))).single()
        assertNull(chosenSource(three, SourcePreference.PREFER_PLEX, setOf("abs1", "abs2")))
    }

    @Test
    fun `always ask asks, and a single source never asks`() {
        val entry = merge(listOf(item("a", "abs1"), item("p", "plex1"))).single()
        assertNull(chosenSource(entry, SourcePreference.ASK, everyServer))
        val single = item("a", "abs1")
        for (preference in SourcePreference.entries) assertEquals(single, chosenSource(single, preference, emptySet()))
    }

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
}
