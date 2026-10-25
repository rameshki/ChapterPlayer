package com.chapterplayer.domain.car

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarSessionTest {

    private val own = "com.chapterplayer"
    private val gearhead = "com.google.android.projection.gearhead"
    private val assistant = "com.google.android.googlequicksearchbox"
    private val systemUi = "com.android.systemui"

    private fun book(
        id: String,
        title: String,
        author: String = "Jane Doe",
        series: String? = null,
        seq: String? = null,
        source: ServerSource = ServerSource.ABS,
        state: DownloadState = DownloadState.NOT_DOWNLOADED,
        progress: ReadingProgress? = null,
        chapters: List<Chapter> = emptyList(),
    ) = BrowseItem(
        AudioBook(
            id = id, title = title, author = author, narrator = "", durationMs = 10_000_000, chapters = chapters,
            streamUrl = "", coverUrl = "cover/$id", source = source, serverId = "s1",
            seriesName = series, seriesSequence = seq,
        ),
        progress,
        state,
    )

    private fun progress(id: String, pos: Long, last: Long, finished: Boolean = false) =
        ReadingProgress(id, pos, 10_000_000, last, finished)

    private fun started(r: CarPlay) = (r as? CarPlay.Start)?.book?.id

    // -- Access ------------------------------------------------------------------------------

    @Test
    fun `this app with a verified package gets everything`() {
        val a = carAccess(own, own, packageVerified = true)
        assertEquals(CarAccess.FULL, a)
        assertTrue(a.canBrowse && a.canStartBooks && a.canSetOwnTracks)
    }

    @Test
    fun `a verified car package may browse and start books but never queue its own tracks`() {
        for (p in listOf(gearhead, assistant, systemUi)) {
            val a = carAccess(p, own, packageVerified = true)
            assertEquals(CarAccess.CAR, a, p)
            assertTrue(a.canBrowse && a.canStartBooks && !a.canSetOwnTracks, p)
        }
    }

    @Test
    fun `an unverified car package may start books of the library but not browse`() {
        val a = carAccess(gearhead, own, packageVerified = false)
        assertEquals(CarAccess.CAR_UNVERIFIED, a)
        assertTrue(!a.canBrowse && a.canStartBooks && !a.canSetOwnTracks)
    }

    @Test
    fun `an unverified claim to be this app gets transport only`() {
        val a = carAccess(own, own, packageVerified = false)
        assertEquals(CarAccess.TRANSPORT_ONLY, a)
        assertTrue(!a.canBrowse && !a.canStartBooks && !a.canSetOwnTracks)
    }

    @Test
    fun `anyone else gets transport only, even with a look-alike package`() {
        for (p in listOf("com.example.player", "com.google.android.projection.gearhead.evil", "com.google.android", "", null)) {
            assertEquals(CarAccess.TRANSPORT_ONLY, carAccess(p, own, packageVerified = true), p.toString())
        }
    }

    // -- Requests ----------------------------------------------------------------------------

    @Test
    fun `requests are read from the media id or the search query`() {
        assertEquals(CarRequest.Book("abs_1"), parseCarRequest("abs_1", null, hasUri = false))
        assertEquals(CarRequest.Search("dune"), parseCarRequest("", "  dune ", hasUri = false))
        assertEquals(CarRequest.Search(""), parseCarRequest("", "", hasUri = false))
        assertEquals(CarRequest.Chapter(3), parseCarRequest("car:chapter:3", null, hasUri = false))
    }

    @Test
    fun `uris, folders, broken chapters and empty requests are refused`() {
        assertEquals(CarRequest.Refused, parseCarRequest("abs_1", null, hasUri = true))
        assertEquals(CarRequest.Refused, parseCarRequest("", "dune", hasUri = true))
        assertEquals(CarRequest.Refused, parseCarRequest("car:continue", null, hasUri = false))
        assertEquals(CarRequest.Refused, parseCarRequest("car:series:x", null, hasUri = false))
        assertEquals(CarRequest.Refused, parseCarRequest("car:chapter:x", null, hasUri = false))
        assertEquals(CarRequest.Refused, parseCarRequest("car:chapter:-1", null, hasUri = false))
        assertEquals(CarRequest.Refused, parseCarRequest("", null, hasUri = false))
        // A track id of our own player is not something a car may ask for.
        assertEquals(CarRequest.Refused, parseCarRequest("abs_1/0", null, hasUri = false))
    }

    @Test
    fun `a book id resolves only to a playable book of the library`() {
        val items = listOf(
            book("abs_1", "One"),
            book("plex_1", "Plex", source = ServerSource.PLEX),
        )
        assertEquals("abs_1", started(resolveCarRequest(CarRequest.Book("abs_1"), items, online = true, nowPlaying = null)))
        assertEquals(CarPlay.Fail(CarFailure.UNKNOWN_BOOK), resolveCarRequest(CarRequest.Book("abs_gone"), items, true, null))
        assertEquals("plex_1", started(resolveCarRequest(CarRequest.Book("plex_1"), items, online = true, nowPlaying = null)))
        assertEquals(CarPlay.Fail(CarFailure.OFFLINE), resolveCarRequest(CarRequest.Book("plex_1"), items, false, null))
        assertEquals(CarPlay.Fail(CarFailure.OFFLINE), resolveCarRequest(CarRequest.Book("abs_1"), items, false, null))
        assertEquals(CarPlay.Fail(CarFailure.REFUSED), resolveCarRequest(CarRequest.Refused, items, true, null))
    }

    @Test
    fun `offline, the book that is already loaded still plays`() {
        val items = listOf(book("abs_1", "One"))
        val loaded = CarNowPlaying("abs_1", "One", emptyList())
        assertEquals("abs_1", started(resolveCarRequest(CarRequest.Book("abs_1"), items, online = false, nowPlaying = loaded)))
    }

    @Test
    fun `offline, a downloaded book plays`() {
        val items = listOf(book("abs_1", "One", state = DownloadState.DOWNLOADED))
        assertEquals("abs_1", started(resolveCarRequest(CarRequest.Book("abs_1"), items, online = false, nowPlaying = null)))
    }

    @Test
    fun `an empty search resumes the last played unfinished book`() {
        val items = listOf(
            book("a", "A", progress = progress("a", 1_000, last = 10)),
            book("b", "B", progress = progress("b", 2_000, last = 30)),
            book("c", "C", progress = progress("c", 2_000, last = 50, finished = true)),
        )
        assertEquals("b", started(resolveCarRequest(CarRequest.Search(""), items, true, null)))
        assertEquals(CarPlay.Fail(CarFailure.NOTHING_TO_RESUME), resolveCarRequest(CarRequest.Search(""), listOf(book("x", "X")), true, null))
    }

    @Test
    fun `a search plays the best title match, and fails clearly when nothing matches`() {
        val items = listOf(
            book("a", "Dune Messiah"),
            book("b", "Dune"),
            book("c", "Children of Dune"),
        )
        assertEquals("b", started(resolveCarRequest(CarRequest.Search("dune"), items, true, null)))
        assertEquals("a", started(resolveCarRequest(CarRequest.Search("Dune: Messiah"), items, true, null)))
        assertEquals(CarPlay.Fail(CarFailure.NO_MATCH), resolveCarRequest(CarRequest.Search("zzz"), items, true, null))
    }

    @Test
    fun `a series name plays the book being listened to, else the first unfinished one in order`() {
        val series = listOf(
            book("s3", "Third", series = "Saga", seq = "3"),
            book("s1", "First", series = "Saga", seq = "1", progress = progress("s1", 10_000_000, 5, finished = true)),
            book("s2", "Second", series = "Saga", seq = "2"),
        )
        assertEquals("s2", started(resolveCarRequest(CarRequest.Search("saga"), series, true, null)))
        val listening = series + book("s4", "Fourth", series = "Saga", seq = "4", progress = progress("s4", 500, 9))
        assertEquals("s4", started(resolveCarRequest(CarRequest.Search("saga"), listening, true, null)))
    }

    @Test
    fun `an author name plays a Plex book like any other`() {
        val items = listOf(
            book("p", "Alpha", author = "Ann Writer", source = ServerSource.PLEX),
            book("a", "Beta", author = "Ann Writer"),
        )
        assertEquals("p", started(resolveCarRequest(CarRequest.Search("ann writer"), items, true, null)))
        assertEquals("p", started(resolveCarRequest(CarRequest.Search("ann writer"), items.take(1), true, null)))
    }

    @Test
    fun `a chapter request seeks in the loaded book only`() {
        val chapters = listOf(Chapter(0, "One", 0, 100), Chapter(1, "Two", 100, 200))
        val now = CarNowPlaying("abs_1", "Book", chapters)
        assertEquals(CarPlay.SeekChapter(1), resolveCarRequest(CarRequest.Chapter(1), emptyList(), true, now))
        assertEquals(CarPlay.Fail(CarFailure.NO_CHAPTER), resolveCarRequest(CarRequest.Chapter(2), emptyList(), true, now))
        assertEquals(CarPlay.Fail(CarFailure.NO_CHAPTER), resolveCarRequest(CarRequest.Chapter(0), emptyList(), true, null))
    }

    // -- Resume on connect -------------------------------------------------------------------

    private val now = 1_000_000L
    private val history = listOf(book("a", "A", progress = progress("a", 1_000, last = now - 3_600_000)))

    private fun resume(
        enabled: Boolean = true,
        pkg: String? = gearhead,
        verified: Boolean = true,
        playerBusy: Boolean = false,
        items: List<BrowseItem> = history,
        online: Boolean = true,
    ) = carConnectResume(enabled, pkg, verified, playerBusy, items, online, now)?.id

    @Test
    fun `the car connecting resumes the last played book when the switch is on and nothing plays`() {
        assertEquals("a", resume())
        assertEquals("a", resume(pkg = "com.google.android.autosimulator"))
    }

    @Test
    fun `no resume with the switch off, or when a book is already playing or loaded`() {
        assertNull(resume(enabled = false))
        assertNull(resume(playerBusy = true))
    }

    @Test
    fun `the car connecting twice tries once, and again after it was gone`() {
        val t = CarConnectionTracker<String>()
        assertTrue(t.connected("browser", gearhead, true))
        assertTrue(!t.connected("controller", gearhead, true))
        assertTrue(!t.connected("browser2", gearhead, true))
        t.disconnected("browser")
        t.disconnected("browser2")
        // One connection is still open: the same drive.
        assertTrue(!t.connected("browser3", gearhead, true))
        t.disconnected("controller")
        t.disconnected("browser3")
        assertTrue(t.connected("browser4", gearhead, true))
    }

    @Test
    fun `only verified head units count as the car connecting`() {
        val t = CarConnectionTracker<String>()
        assertTrue(!t.connected("x", gearhead, packageVerified = false))
        assertTrue(!t.connected("y", assistant, true))
        assertTrue(!t.connected("z", own, true))
        // Unrelated disconnects change nothing.
        t.disconnected("unknown")
        assertTrue(t.connected("w", gearhead, true))
    }

    @Test
    fun `only the car itself triggers it, verified, not the assistant or the system ui`() {
        assertNull(resume(pkg = assistant))
        assertNull(resume(pkg = systemUi))
        assertNull(resume(pkg = own))
        assertNull(resume(pkg = null))
        assertNull(resume(verified = false))
    }

    @Test
    fun `nothing played yet, or only finished books, means no resume`() {
        assertNull(resume(items = emptyList()))
        assertNull(resume(items = listOf(book("x", "X"))))
        assertNull(resume(items = listOf(book("f", "F", progress = progress("f", 10_000_000, now - 3_600_000, finished = true)))))
    }

    @Test
    fun `a book paused within the last minute is not restarted, and unplayable books are skipped`() {
        assertNull(resume(items = listOf(book("a", "A", progress = progress("a", 1_000, last = now - 30_000)))))
        assertEquals("a", resume(items = listOf(book("a", "A", progress = progress("a", 1_000, last = now - 61_000)))))
        assertNull(resume(online = false))
        assertEquals("p", resume(items = listOf(book("p", "P", source = ServerSource.PLEX, progress = progress("p", 1_000, now - 3_600_000)))))
    }

    // -- Lists -------------------------------------------------------------------------------

    @Test
    fun `pages are slices of the list`() {
        val list = (0 until 10).toList()
        assertEquals(listOf(0, 1, 2, 3), carPage(list, 0, 4))
        assertEquals(listOf(8, 9), carPage(list, 2, 4))
        assertEquals(emptyList(), carPage(list, 3, 4))
        assertEquals(list, carPage(list, 0, Int.MAX_VALUE))
        assertEquals(emptyList(), carPage(list, Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(carPage(list, -1, 4))
        assertNull(carPage(list, 0, 0))
    }

    @Test
    fun `an unknown parent has no children`() {
        val d = CarData(emptyList(), emptyList())
        assertNull(carChildrenFor("car:nothing", d, true, null))
        assertNull(carChildrenFor("abs_1", d, true, null))
        assertNull(carChildrenFor("", d, true, null))
    }

    @Test
    fun `while a book with chapters is loaded, Continue starts with its chapters`() {
        val chapters = listOf(Chapter(0, "Opening", 0, 65_000), Chapter(1, "", 65_000, 200_000))
        val d = CarData(listOf(book("abs_1", "Book", progress = progress("abs_1", 1_000, 10))), emptyList())
        val now = CarNowPlaying("abs_1", "Book", chapters)
        val cont = carChildrenFor("car:continue", d, true, now)!!
        assertEquals(listOf("car:chapters", "abs_1"), cont.map { it.id })
        assertTrue(cont[0].browsable && !cont[0].playable)
        val list = carChildrenFor("car:chapters", d, true, now)!!
        assertEquals(listOf("car:chapter:0", "car:chapter:1"), list.map { it.id })
        assertEquals(listOf("Opening", "Chapter 2"), list.map { it.title })
        assertEquals(listOf("0:00", "1:05"), list.map { it.subtitle })
        assertTrue(list.all { it.playable && !it.browsable && !it.dimmed })
        // No loaded book or no chapters: no chapters row, an empty chapters list.
        assertEquals(listOf("abs_1"), carChildrenFor("car:continue", d, true, null)!!.map { it.id })
        assertEquals(listOf("abs_1"), carChildrenFor("car:continue", d, true, now.copy(chapters = emptyList()))!!.map { it.id })
        assertEquals(emptyList(), carChildrenFor("car:chapters", d, true, null))
    }

    @Test
    fun `items are found by id for nodes, books and chapters`() {
        val d = CarData(listOf(book("abs_1", "Book", series = "Saga")), emptyList())
        val now = CarNowPlaying("abs_1", "Book", listOf(Chapter(0, "Opening", 0, 100)))
        assertEquals("car:root", carItemFor("car:root", d, true, now)?.id)
        assertEquals("Recently added", carItemFor("car:recent", d, true, now)?.title)
        assertEquals("All books", carItemFor("car:all", d, true, now)?.title)
        assertEquals("Saga", carItemFor("car:series:${carChildren("car:series", d, true)!!.single().id.substringAfter("car:series:")}", d, true, now)?.title)
        assertEquals("Book", carItemFor("abs_1", d, true, now)?.title)
        assertEquals("Opening", carItemFor("car:chapter:0", d, true, now)?.title)
        assertEquals("Chapters", carItemFor("car:chapters", d, true, now)?.title)
        assertNull(carItemFor("abs_gone", d, true, now))
        assertNull(carItemFor("car:chapter:5", d, true, now))
        assertNull(carItemFor("car:nothing", d, true, now))
    }

    @Test
    fun `search results list matches best first, and an empty query shows the book to resume`() {
        val d = CarData(
            listOf(
                book("a", "Dune Messiah"),
                book("b", "Dune", progress = progress("b", 1_000, 10)),
                book("c", "Other"),
            ),
            emptyList(),
        )
        assertEquals(listOf("b", "a"), carSearchResults("dune", d, true).map { it.id })
        assertEquals(listOf("b"), carSearchResults("  ", d, true).map { it.id })
        assertEquals(emptyList(), carSearchResults("zzz", d, true))
    }

    @Test
    fun `failure messages are plain words`() {
        for (reason in CarFailure.entries) {
            val text = carFailureMessage(reason)
            assertTrue(text.isNotBlank() && text.none { it == '/' || it == ':' }, text)
        }
    }

    @Test
    fun `the player holds a book when every item is one of its files`() {
        assertTrue(playerHoldsBook(listOf("abs_1/0", "abs_1/1"), "abs_1"))
        assertTrue(!playerHoldsBook(emptyList(), "abs_1"))
        assertTrue(!playerHoldsBook(listOf("abs_1/0", "abs_2/1"), "abs_1"))
        assertTrue(!playerHoldsBook(listOf("abs_10/0"), "abs_1"))
        assertTrue(!playerHoldsBook(listOf("abs_1/"), "abs_1"))
        assertTrue(!playerHoldsBook(listOf("abs_1/x"), "abs_1"))
    }
}
