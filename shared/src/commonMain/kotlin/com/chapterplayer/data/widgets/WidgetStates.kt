package com.chapterplayer.data.widgets

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.domain.widgets.NowPlayingWidgetState
import com.chapterplayer.domain.widgets.continueWidgetState
import com.chapterplayer.domain.widgets.nowPlayingWidgetState
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the home screen widgets show, from the same sources as the app: the player for Now Playing, the books,
 * progress and downloads for Continue Listening. Each flow only emits when what a widget draws changes.
 */
class WidgetStates(
    private val controller: PlaybackController,
    private val books: BookRepository,
    private val downloads: DownloadStates,
    private val connectivity: ConnectivityMonitor,
    private val settings: AppSettings,
) {

    val nowPlaying: Flow<NowPlayingWidgetState>
        get() = combine(controller.state, books.observeAllBooks()) { state, all ->
            nowPlayingWidgetState(state, state.bookId?.let { id -> all.firstOrNull { it.id == id } })
        }.distinctUntilChanged()

    val continueListening: Flow<ContinueWidgetState>
        get() = combine(observeBrowseItems(books, downloads), connectivity.isOnline, settings.downloadedOnly) { items, online, downloadedOnly ->
            continueWidgetState(items, online, downloadedOnly)
        }.distinctUntilChanged()
}
