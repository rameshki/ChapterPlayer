package com.chapterplayer.domain.widgets

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.CANNOT_REACH_TEXT
import com.chapterplayer.data.playback.NEEDS_ONE_ONLINE_PLAY_TEXT
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.SIGN_IN_AGAIN_TEXT
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.playback.NEEDS_CONNECTION_TO_PLAY_TEXT
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetStartReactionTest {

    private fun book(id: String, server: String = "s1") = AudioBook(
        id = id, title = "Title", author = "Author", narrator = "", durationMs = 3_600_000,
        chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = server,
    )

    @Test
    fun signInAgainOffersTheApp() {
        assertEquals(WidgetStartReaction(SIGN_IN_AGAIN_TEXT, offerApp = true), widgetStartReaction(SIGN_IN_AGAIN_TEXT))
    }

    @Test
    fun otherFailuresOnlyShowAShortReason() {
        assertEquals(WidgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT, offerApp = false), widgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT))
        assertEquals(WidgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT, offerApp = false), widgetStartReaction(NEEDS_ONE_ONLINE_PLAY_TEXT))
        assertEquals(WidgetStartReaction(SERVER_NOT_REACHABLE_TEXT, offerApp = false), widgetStartReaction(CANNOT_REACH_TEXT))
        assertEquals(WidgetStartReaction(CANT_PLAY_HERE_TEXT, offerApp = false), widgetStartReaction(null))
        assertEquals(WidgetStartReaction(CANT_PLAY_HERE_TEXT, offerApp = false), widgetStartReaction(PlaybackStartException.DEFAULT_MESSAGE))
        assertEquals(WidgetStartReaction(ANOTHER_BOOK_PLAYING_TEXT, offerApp = false), widgetStartReaction(ANOTHER_BOOK_PLAYING_TEXT))
        // A start's own wording passes through (it is already safe and plain).
        assertEquals("This book's audio format cannot be played on this phone", widgetStartReaction("This book's audio format cannot be played on this phone").text)
    }

    @Test
    fun nothingLoadedStartsTheTappedBook() {
        assertEquals(WidgetPlayDecision.Start, widgetPlayDecision(null, "b", emptyList()))
    }

    @Test
    fun theSameBookOrAnotherCopyOfItIsResumedNotReloaded() {
        assertEquals(WidgetPlayDecision.PlayLoaded("a"), widgetPlayDecision("a", "a", emptyList()))
        // One entry with two copies: the loaded copy plays, the tapped copy is not loaded over it.
        val merged = BrowseItem(book("abs_a"), null, DownloadState.NOT_DOWNLOADED).let { it.copy(sources = listOf(it, BrowseItem(book("plex_a", "s2"), null, DownloadState.NOT_DOWNLOADED))) }
        assertEquals(WidgetPlayDecision.PlayLoaded("abs_a"), widgetPlayDecision("abs_a", "plex_a", listOf(merged)))
    }

    @Test
    fun anotherBookPlayingIsNeverReplaced() {
        // The car plays A while Continue Listening offers B (or a stale Listen card still shows B).
        val items = listOf(BrowseItem(book("a"), null, DownloadState.NOT_DOWNLOADED), BrowseItem(book("b"), null, DownloadState.NOT_DOWNLOADED))
        assertEquals(WidgetPlayDecision.OtherBookLoaded, widgetPlayDecision("a", "b", items))
        // A loaded book the library does not know (yet) is not replaced either.
        assertEquals(WidgetPlayDecision.OtherBookLoaded, widgetPlayDecision("unknown", "b", items))
    }

    @Test
    fun aRefusedForegroundStartPausesAndNotifiesOnlyWithABookLoaded() {
        assertEquals(ForegroundRefusalAction.PAUSE_AND_NOTIFY, foregroundRefusalAction(bookLoaded = true))
        assertEquals(ForegroundRefusalAction.NOTHING, foregroundRefusalAction(bookLoaded = false))
    }
}
