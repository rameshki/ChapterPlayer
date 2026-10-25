package com.chapterplayer.domain.widgets

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BrowseItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetResumeRulesTest {

    @Test
    fun aFreshTapIsHonoured() {
        assertTrue(shouldHandleWidgetResume(restoredFromSavedState = false, launchedFromHistory = false))
    }

    @Test
    fun aRestoredOrRecentsLaunchIsStale() {
        // After process death the activity comes back with its old intent; from Recents it is replayed.
        assertFalse(shouldHandleWidgetResume(restoredFromSavedState = true, launchedFromHistory = false))
        assertFalse(shouldHandleWidgetResume(restoredFromSavedState = false, launchedFromHistory = true))
        assertFalse(shouldHandleWidgetResume(restoredFromSavedState = true, launchedFromHistory = true))
    }

    private fun item(id: String, positionMs: Long?, lastPlayedAt: Long, state: DownloadState = DownloadState.NOT_DOWNLOADED): BrowseItem {
        val book = AudioBook(
            id = id, title = "Title $id", author = "Author", narrator = "Narrator", durationMs = 100_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1",
        )
        return BrowseItem(book, positionMs?.let { ReadingProgress(id, it, 100_000L, lastPlayedAt) }, state)
    }

    @Test
    fun theOfferedBookAndHomesResumeBookMayBeResumed() {
        val items = listOf(
            item("streamed", 10_000L, lastPlayedAt = 9),
            item("onPhone", 10_000L, lastPlayedAt = 1, state = DownloadState.DOWNLOADED),
            item("untouched", null, lastPlayedAt = 0),
        )
        // Offline the widget offers the downloaded book; the streamed one is still Home's Resume book, so the
        // starter gets to say why it cannot play (a widget drawn before the connection went).
        assertTrue(widgetMayResume(items, online = false, downloadedOnly = false, bookId = "onPhone"))
        assertTrue(widgetMayResume(items, online = false, downloadedOnly = false, bookId = "streamed"))
        assertFalse(widgetMayResume(items, online = true, downloadedOnly = false, bookId = "untouched"))
        assertFalse(widgetMayResume(items, online = true, downloadedOnly = false, bookId = "missing"))
    }
}
