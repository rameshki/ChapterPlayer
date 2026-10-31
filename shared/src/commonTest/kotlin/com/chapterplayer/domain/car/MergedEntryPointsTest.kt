package com.chapterplayer.domain.car

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.SourcePreference
import com.chapterplayer.domain.browse.mergeDuplicates
import com.chapterplayer.domain.browse.resumeCandidate
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.domain.widgets.continueWidgetState
import com.chapterplayer.domain.widgets.widgetMayResume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Home, the car and the widgets with a book that two servers have: they resume the copy played last. */
class MergedEntryPointsTest {

    private val gearhead = "com.google.android.projection.gearhead"
    private val hour = 3_600_000L

    private fun copy(id: String, serverId: String, lastPlayedAt: Long? = null, state: DownloadState = DownloadState.NOT_DOWNLOADED) = BrowseItem(
        AudioBook(
            id = id, title = "Same Book", author = "Same Author", narrator = "", durationMs = 10 * hour, chapters = emptyList(),
            streamUrl = "", coverUrl = null, source = if (serverId.startsWith("plex")) ServerSource.PLEX else ServerSource.ABS, serverId = serverId,
        ),
        lastPlayedAt?.let { ReadingProgress(id, hour, 10 * hour, it) },
        state,
    )

    // a (abs1) is the primary; p (plex1) was played last.
    private fun items(state: DownloadState = DownloadState.NOT_DOWNLOADED, online: Set<String> = setOf("abs1", "plex1")) =
        mergeDuplicates(
            listOf(copy("a", "abs1", lastPlayedAt = 100L, state = state), copy("p", "plex1", lastPlayedAt = 500L)),
            MergeOverrides(), SourcePreference.PREFER_DOWNLOADED, online,
        )

    private fun started(r: CarPlay) = (r as? CarPlay.Start)?.book?.id

    @Test
    fun `the entry's primary is not the copy played last`() {
        assertEquals("a", items().single().book.id)
        assertEquals("p", resumeCandidate(items())?.item?.book?.id)
    }

    @Test
    fun `the car resumes the copy played last - by request and by an empty voice search`() {
        assertEquals("p", started(resumeLast(items(), online = true, nowPlaying = null)))
        assertEquals("p", started(resolveCarRequest(CarRequest.Search(""), items(), online = true, nowPlaying = null)))
        assertEquals(listOf("a"), carSearchResults("", CarData(items(), emptyList()), online = true).map { it.id })
    }

    @Test
    fun `offline the car resumes a downloaded copy instead`() {
        val offline = items(state = DownloadState.DOWNLOADED, online = emptySet())
        assertEquals("a", started(resumeLast(offline, online = false, nowPlaying = null)))
    }

    @Test
    fun `car connect resume guards and starts the copy played last`() {
        val start = carConnectResume(true, gearhead, true, playerBusy = false, items(), online = true, nowMs = 500L + CAR_RESUME_PAUSE_GUARD_MS)
        assertEquals("p", start?.id)
        // The guard is measured from the copy played last (500), not the primary (100).
        val tooSoon = carConnectResume(true, gearhead, true, playerBusy = false, items(), online = true, nowMs = 500L + CAR_RESUME_PAUSE_GUARD_MS - 1)
        assertEquals(null, tooSoon)
    }

    @Test
    fun `any copy's id resolves - and a playing copy is never replaced by the primary`() {
        assertEquals("p", started(resolveCarRequest(CarRequest.Book("p"), items(), online = true, nowPlaying = null)))
        val playingP = CarNowPlaying("p", "Same Book", emptyList())
        assertEquals("p", started(resolveCarRequest(CarRequest.Book("a"), items(), online = true, nowPlaying = playingP)))
        assertEquals("p", carItemFor("p", CarData(items(), emptyList()), online = true, nowPlaying = null)?.id)
    }

    @Test
    fun `an id handed out before the primary changed still plays`() {
        // The car was given "p" when Plex was the primary; now a downloaded ABS copy is, and Plex is offline.
        val now = items(state = DownloadState.DOWNLOADED, online = emptySet())
        assertEquals("a", now.single().book.id)
        assertEquals("a", started(resolveCarRequest(CarRequest.Book("p"), now, online = false, nowPlaying = null)))
    }

    @Test
    fun `the Continue widget offers the copy played last and accepts any copy of it`() {
        val state = continueWidgetState(items(), online = true, downloadedOnly = false)
        assertEquals("p", (state as ContinueWidgetState.Book).bookId)
        assertTrue(widgetMayResume(items(), online = true, downloadedOnly = false, bookId = "p"))
        // Drawn earlier with the other copy: still accepted.
        assertTrue(widgetMayResume(items(), online = true, downloadedOnly = false, bookId = "a"))
    }

    @Test
    fun `offline the widget offers the downloaded copy`() {
        val offline = items(state = DownloadState.DOWNLOADED, online = emptySet())
        val state = continueWidgetState(offline, online = false, downloadedOnly = false)
        assertEquals("a", (state as ContinueWidgetState.Book).bookId)
    }
}
