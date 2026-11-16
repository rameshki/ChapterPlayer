package com.chapterplayer.data.merge

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.stateOf
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.SourceChoice
import com.chapterplayer.domain.browse.duplicateGroups
import com.chapterplayer.domain.browse.entryOf
import com.chapterplayer.domain.browse.mergeDuplicates
import com.chapterplayer.domain.browse.normalisedAuthors
import com.chapterplayer.domain.browse.normalisedFirstAuthor
import com.chapterplayer.domain.browse.normalisedTitle
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class MergedLibraryTest {

    private class MutableMerging(context: MergeContext?) : SourceMerging {
        val flow = MutableStateFlow(context)
        override val context: Flow<MergeContext?> = flow
    }

    private val servers = listOf(
        "abs1" to ServerSource.ABS, "abs2" to ServerSource.ABS, "plex1" to ServerSource.PLEX, "jf1" to ServerSource.JELLYFIN,
    )

    /*
     * A synthetic library like a real one across three kinds of server: most titles on one server, many on two or
     * three (lengths a little apart or unknown, edition suffixes, accents, a second author written differently), and
     * some titles shared by different books of one author. Every text is made up.
     */
    private fun syntheticBooks(count: Int, seed: Int): List<AudioBook> {
        val random = Random(seed)
        val titles = (count * 2 / 3).coerceAtLeast(2)
        val suffixes = listOf("", "", "", " (Unabridged)", ": Abridged", " [unabridged edition]")
        val articles = listOf("", "", "The ", "A ")
        return List(count) { i ->
            val t = random.nextInt(titles)
            val (serverId, source) = servers[random.nextInt(servers.size)]
            val baseLength = 3_600_000L * (1 + t % 30)
            val length = when (random.nextInt(10)) {
                0 -> 0L
                1 -> baseLength + 20 * 60_000L
                else -> baseLength + random.nextLong(-60_000L, 60_000L)
            }
            val author = when (random.nextInt(6)) {
                0 -> "Émile Writer$t"
                1 -> "Emile Writer$t and Co Author"
                2 -> "Emile Writer$t, Co Author"
                else -> "Emile Writer$t"
            }
            AudioBook(
                id = "${if (source == ServerSource.PLEX) "plex" else if (source == ServerSource.JELLYFIN) "jf" else "abs"}_$i",
                title = articles[random.nextInt(articles.size)] + "Made Up Title $t" + suffixes[random.nextInt(suffixes.size)],
                author = author, narrator = if (random.nextBoolean()) "Reader $t" else "",
                durationMs = length, chapters = emptyList(), streamUrl = "", coverUrl = null,
                source = source, serverId = serverId, addedAt = i.toLong(),
                seriesName = if (t % 4 == 0 && source == ServerSource.ABS) "Series ${t / 4}" else null,
                seriesSequence = if (t % 4 == 0) "${t % 7}" else null,
            )
        }
    }

    private fun syntheticProgress(books: List<AudioBook>, seed: Int): List<ReadingProgress> {
        val random = Random(seed)
        return books.filter { random.nextInt(3) == 0 }.map {
            ReadingProgress(
                it.id, random.nextLong(0, 1_000_000L), it.durationMs,
                // Ties on purpose, with mirrored rows among them.
                lastPlayedAt = random.nextLong(0, 50), isFinished = random.nextInt(8) == 0, mirrored = random.nextInt(4) == 0,
            )
        }
    }

    private fun syntheticDownloads(books: List<AudioBook>, seed: Int): Map<String, DownloadState> {
        val random = Random(seed)
        return books.filter { random.nextInt(5) == 0 }.associate {
            it.id to if (random.nextBoolean()) DownloadState.DOWNLOADED else DownloadState.DOWNLOADING
        }
    }

    private fun syntheticOverrides(books: List<AudioBook>, seed: Int): MergeOverrides {
        val random = Random(seed)
        fun pair() = books[random.nextInt(books.size)].id to books[random.nextInt(books.size)].id
        return MergeOverrides(splits = List(books.size / 20) { pair() }.toSet(), merges = List(books.size / 20) { pair() }.toSet())
    }

    private val contexts = listOf(
        MergeContext(onlineServerIds = setOf("abs1", "abs2", "plex1", "jf1")),
        MergeContext(playChoice = SourceChoice.OfType(ServerSource.PLEX), onlineServerIds = setOf("plex1", "jf1")),
        MergeContext(playChoice = SourceChoice.OfServer("abs2"), onlineServerIds = setOf("abs2")),
        MergeContext(playChoice = SourceChoice.OfType(ServerSource.JELLYFIN), onlineServerIds = emptySet(), deviceOnline = false),
    )

    // ---- The merge as it was before the shared library (R10), kept verbatim as the reference. ----

    private fun legacyBrowseItems(books: BookRepository, downloads: DownloadStates, merging: SourceMerging): Flow<List<BrowseItem>> =
        combine(books.observeAllBooks(), books.observeAllProgress(), downloads.observe(), merging.context) { allBooks, progress, downloadStates, context ->
            val progressById = progress.associateBy { it.bookId }
            val items = allBooks.map { book -> BrowseItem(book, progressById[book.id], downloadStates.stateOf(book.id)) }
            if (context == null) items else legacyMerge(items, context.overrides, context.playChoice, context.onlineServerIds)
        }

    private fun legacyLengthsAgree(a: Long, b: Long): Boolean {
        if (a <= 0L || b <= 0L) return false
        val diff = abs(a - b)
        return diff <= 5 * 60_000L || diff <= max(a, b) * 0.02
    }

    private fun legacySameBook(a: AudioBook, b: AudioBook): Boolean {
        if (a.durationMs > 0L && b.durationMs > 0L) return legacyLengthsAgree(a.durationMs, b.durationMs)
        val authors = normalisedAuthors(a.author)
        return authors.isNotEmpty() && authors == normalisedAuthors(b.author)
    }

    private fun legacyLengthDiff(a: Long, b: Long): Long = if (a > 0L && b > 0L) abs(a - b) else Long.MAX_VALUE
    private fun legacyPairKey(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a

    private fun legacyMerge(items: List<BrowseItem>, overrides: MergeOverrides, playChoice: SourceChoice, onlineServerIds: Set<String>): List<BrowseItem> {
        if (items.size < 2) return items
        val byId = LinkedHashMap<String, BrowseItem>()
        for (item in items) if (item.book.id !in byId) byId[item.book.id] = item
        val ids = byId.keys.sorted()
        val index = HashMap<String, Int>()
        ids.forEachIndexed { i, id -> index[id] = i }
        val splits = overrides.splits.mapTo(HashSet()) { legacyPairKey(it.first, it.second) }
        val forced = overrides.merges.map { legacyPairKey(it.first, it.second) }
            .filter { it.first != it.second && it.first in index && it.second in index && it !in splits }
            .distinct().sortedWith(compareBy({ it.first }, { it.second }))
        val buckets = HashMap<String, MutableList<String>>()
        for (id in ids) {
            val book = byId.getValue(id).book
            val title = normalisedTitle(book.title)
            val author = normalisedFirstAuthor(book.author)
            if (title.isEmpty() || author.isEmpty()) continue
            buckets.getOrPut("$title\u0000$author") { mutableListOf() }.add(id)
        }
        class Candidate(val a: String, val b: String, val diff: Long)
        val automatic = mutableListOf<Candidate>()
        for (bucket in buckets.values) {
            for (i in bucket.indices) for (j in i + 1 until bucket.size) {
                val a = byId.getValue(bucket[i]).book
                val b = byId.getValue(bucket[j]).book
                if (a.serverId == b.serverId || !legacySameBook(a, b)) continue
                val key = legacyPairKey(a.id, b.id)
                if (key in splits) continue
                automatic += Candidate(key.first, key.second, legacyLengthDiff(a.durationMs, b.durationMs))
            }
        }
        automatic.sortWith(compareBy<Candidate>({ it.diff }, { it.a }, { it.b }))
        val parent = IntArray(ids.size) { it }
        val members = Array(ids.size) { mutableListOf(ids[it]) }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun tryJoin(a: String, b: String) {
            val ra = find(index.getValue(a))
            val rb = find(index.getValue(b))
            if (ra == rb) return
            val left = members[ra]
            val right = members[rb]
            val leftServers = left.mapTo(HashSet()) { byId.getValue(it).book.serverId }
            if (right.any { byId.getValue(it).book.serverId in leftServers }) return
            if (left.any { l -> right.any { r -> legacyPairKey(l, r) in splits } }) return
            val (root, child) = if (ra < rb) ra to rb else rb to ra
            parent[child] = root
            members[root].addAll(members[child])
            members[child].clear()
        }
        for (pair in forced) tryJoin(pair.first, pair.second)
        for (c in automatic) tryJoin(c.a, c.b)
        val emitted = HashSet<Int>()
        val result = ArrayList<BrowseItem>(byId.size)
        for (item in byId.values) {
            val root = find(index.getValue(item.book.id))
            val group = members[root]
            if (group.size < 2) { result += item; continue }
            if (!emitted.add(root)) continue
            result += entryOf(group.map { byId.getValue(it) }, playChoice, onlineServerIds)
        }
        return result
    }

    // ---- (c) the same results as before ----

    @Test
    fun `the split merge gives the old results on synthetic libraries`() {
        for (seed in 1..12) {
            val books = syntheticBooks(400, seed)
            val progress = syntheticProgress(books, seed).associateBy { it.bookId }
            val downloads = syntheticDownloads(books, seed)
            val items = books.map { BrowseItem(it, progress[it.id], downloads.stateOf(it.id)) }
            for (overrides in listOf(MergeOverrides(), syntheticOverrides(books, seed))) {
                val groups = duplicateGroups(books, overrides)
                for (context in contexts) {
                    val expected = legacyMerge(items, overrides, context.playChoice, context.onlineServerIds)
                    assertTrue(expected.count { it.isMerged } > 10, "the fixture has merged entries")
                    assertEquals(expected, groups.entries(items, context.playChoice, context.onlineServerIds), "seed $seed")
                    assertEquals(expected, mergeDuplicates(items, overrides, context.playChoice, context.onlineServerIds), "seed $seed")
                }
            }
        }
    }

    @Test
    fun `small and repeated inputs give the old results`() {
        val books = syntheticBooks(30, 7)
        val items = books.map { BrowseItem(it, null, DownloadState.NOT_DOWNLOADED) }
        val context = contexts.first()
        for (input in listOf(emptyList(), items.take(1), items.take(2), items + items.take(5), items.reversed())) {
            assertEquals(
                legacyMerge(input, context.overrides, context.playChoice, context.onlineServerIds),
                mergeDuplicates(input, context.overrides, context.playChoice, context.onlineServerIds),
            )
        }
    }

    @Test
    fun `the shared library follows every change like the old flow`() = runTest {
        val all = syntheticBooks(300, 3)
        val books = FakeBookRepository(all)
        books.progress.value = syntheticProgress(all, 3)
        val downloads = FakeDownloadStates(syntheticDownloads(all, 3))
        val merging = MutableMerging(contexts[0])
        val library = MergedLibrary(books, downloads, merging, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val seen = subscribe(library, 2)
        val legacy = legacyBrowseItems(books, downloads, merging)

        suspend fun check() {
            runCurrent()
            assertEquals(legacy.first(), seen.last())
            assertEquals(legacy.first(), library.items.first())
        }
        check()
        books.progress.value = syntheticProgress(all, 4); check()
        downloads.states.value = syntheticDownloads(all, 5); check()
        merging.flow.value = contexts[1]; check()
        merging.flow.value = contexts[2].copy(overrides = syntheticOverrides(all, 6)); check()
        books.books.value = all.drop(17) + syntheticBooks(40, 8).map { it.copy(id = it.id + "_new") }; check()
        merging.flow.value = null; check()
        merging.flow.value = contexts[3]; check()
    }

    // ---- (a) one merge per change, (b) no grouping for progress ----

    private fun TestScope.subscribe(library: MergedLibrary, collectors: Int): MutableList<List<BrowseItem>> {
        val seen = mutableListOf<List<BrowseItem>>()
        repeat(collectors) { n ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                library.items.collect { if (n == 0) seen += it }
            }
        }
        runCurrent()
        return seen
    }

    @Test
    fun `one merge per change however many readers collect`() = runTest {
        val all = syntheticBooks(200, 9)
        val books = FakeBookRepository(all)
        val downloads = FakeDownloadStates()
        val library = MergedLibrary(books, downloads, MutableMerging(contexts[0]), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        subscribe(library, 6)
        assertEquals(1, library.runs.lists)
        assertEquals(1, library.runs.groupings)

        books.progress.value = syntheticProgress(all, 1)
        runCurrent()
        assertEquals(2, library.runs.lists)
        downloads.states.value = syntheticDownloads(all, 1)
        runCurrent()
        assertEquals(3, library.runs.lists)
        // A late reader gets the shared list without a merge of its own.
        library.items.first()
        assertEquals(3, library.runs.lists)
        assertEquals(1, library.runs.groupings)
    }

    @Test
    fun `without a scope every reader merges on its own`() = runTest {
        val all = syntheticBooks(50, 2)
        val books = FakeBookRepository(all)
        val library = MergedLibrary(books, FakeDownloadStates(), MutableMerging(contexts[0]), scope = null, dispatcher = UnconfinedTestDispatcher(testScheduler))
        subscribe(library, 3)
        assertEquals(3, library.runs.lists)
        assertEquals(3, library.runs.groupings)
    }

    @Test
    fun `progress downloads and choices do not group again but books and corrections do`() = runTest {
        val all = syntheticBooks(200, 11)
        // Like Room: a new list on every change of the table, even an equal one (a StateFlow would drop it).
        val bookRows = MutableSharedFlow<List<AudioBook>>(replay = 1).apply { tryEmit(all) }
        val books = object : FakeBookRepository(all) { override fun observeAllBooks(): Flow<List<AudioBook>> = bookRows }
        val downloads = FakeDownloadStates()
        val merging = MutableMerging(contexts[0])
        val library = MergedLibrary(books, downloads, merging, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        subscribe(library, 2)
        assertEquals(1, library.runs.groupings)

        repeat(5) { books.progress.value = syntheticProgress(all, 100 + it); runCurrent() }
        downloads.states.value = syntheticDownloads(all, 12); runCurrent()
        merging.flow.value = contexts[1]; runCurrent()
        merging.flow.value = contexts[2]; runCurrent()
        // An equal list of books (Room emits a new list for any change of the table) keeps the groups too.
        bookRows.emit(all.toList()); runCurrent()
        assertEquals(1, library.runs.groupings)
        assertEquals(10, library.runs.lists)

        merging.flow.value = contexts[2].copy(overrides = syntheticOverrides(all, 13)); runCurrent()
        assertEquals(2, library.runs.groupings)
        bookRows.emit(all.drop(1)); runCurrent()
        assertEquals(3, library.runs.groupings)
    }

    @Test
    fun `the share forgets its list once nobody reads it`() = runTest {
        val books = FakeBookRepository(syntheticBooks(20, 1))
        val library = MergedLibrary(books, FakeDownloadStates(), MutableMerging(contexts[0]), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        library.items.first()
        val shared = library.items as SharedFlow<List<BrowseItem>>
        assertEquals(1, shared.replayCache.size)
        advanceTimeBy(MergedLibrary.STOP_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(0, shared.replayCache.size)
        // The next reader starts a fresh merge.
        library.items.first()
        assertEquals(2, library.runs.lists)
    }

    // ---- failures upstream ----

    // Download states whose source fails [failures] times before it works (Room throwing, for instance).
    private class FlakyDownloads(private val failures: Int) : DownloadStates {
        var opened = 0
        override fun observe(): Flow<Map<String, DownloadState>> = kotlinx.coroutines.flow.flow {
            opened++
            if (opened <= failures) throw IllegalStateException("made-up failure")
            emit(emptyMap())
        }
    }

    @Test
    fun `a failure upstream is retried and the list still comes`() = runTest {
        val all = syntheticBooks(30, 5)
        val downloads = FlakyDownloads(failures = 1)
        val library = MergedLibrary(FakeBookRepository(all), downloads, MutableMerging(contexts[0]), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val list = library.items.first()
        assertEquals(legacyMerge(all.map { BrowseItem(it, null, DownloadState.NOT_DOWNLOADED) }, MergeOverrides(), SourceChoice.Ask, contexts[0].onlineServerIds), list)
        assertEquals(2, downloads.opened)
    }

    @Test
    fun `an upstream that keeps failing does not crash the scope`() = runTest {
        val downloads = FlakyDownloads(failures = Int.MAX_VALUE)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val library = MergedLibrary(FakeBookRepository(syntheticBooks(10, 1)), downloads, MutableMerging(contexts[0]), scope, UnconfinedTestDispatcher(testScheduler))
        val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { library.items.collect { } }
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive, "the scope lives on")
        assertTrue(downloads.opened > 5, "it kept retrying")
        reader.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test
    fun `the retry wait doubles up to a minute`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..8).map { MergedLibrary.retryDelayMs(it) })
    }

    // ---- benchmark ----

    /*
     * 5,000 books on four servers of three kinds, three readers, then 20 progress saves. Measured on the dev machine
     * (JDK 17, Windows, 2026-11-16): first merge with grouping about 35 ms, then about 3 ms per progress save for all
     * three readers together; the old merge took about 28 ms per save for EACH reader (it grouped again every time).
     * The time bounds below are hundreds of times wider so a slow CI machine never fails; the run counts are the
     * real check.
     */
    @Test
    fun `five thousand books merge once and a progress save stays cheap`() = runTest {
        val all = syntheticBooks(5_000, 42)
        val books = FakeBookRepository(all)
        val merging = MutableMerging(contexts[0])
        val library = MergedLibrary(books, FakeDownloadStates(syntheticDownloads(all, 42)), merging, backgroundScope, UnconfinedTestDispatcher(testScheduler))

        val first = TimeSource.Monotonic.markNow()
        val seen = subscribe(library, 3)
        val firstMs = first.elapsedNow().inWholeMilliseconds
        assertTrue(seen.single().size < all.size, "some books are merged")

        val saves = TimeSource.Monotonic.markNow()
        repeat(20) {
            books.progress.value = syntheticProgress(all, it)
            runCurrent()
        }
        val perSaveMs = saves.elapsedNow().inWholeMilliseconds / 20.0
        // For comparison: the old merge, which grouped again on every save.
        val items = seen.last().flatMap { it.allSources }
        val old = TimeSource.Monotonic.markNow()
        repeat(5) { legacyMerge(items, MergeOverrides(), SourceChoice.Ask, contexts[0].onlineServerIds) }
        val oldMs = old.elapsedNow().inWholeMilliseconds / 5.0
        println("MergedLibrary benchmark: 5000 books, first merge $firstMs ms, $perSaveMs ms per progress save, old merge $oldMs ms per save")

        assertEquals(1, library.runs.groupings)
        assertEquals(21, library.runs.lists)
        assertTrue(firstMs < 15_000, "first merge took $firstMs ms")
        assertTrue(perSaveMs < 1_000, "a progress save took $perSaveMs ms")
    }
}
