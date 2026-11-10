package com.chapterplayer.data.widgets

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.domain.widgets.listenWidgetState
import com.chapterplayer.domain.widgets.NowPlayingWidgetState
import com.chapterplayer.domain.widgets.continueWidgetState
import com.chapterplayer.domain.widgets.nowPlayingWidgetState
import com.chapterplayer.domain.widgets.widgetMayResume
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.widgets.WidgetPlayDecision
import com.chapterplayer.domain.widgets.widgetPlayDecision

/** The merged library and what decides what can play now: one computation shared by the three widgets. */
private data class Library(val items: List<BrowseItem>, val online: Boolean, val downloadedOnly: Boolean)

/**
 * What the home screen widgets show, from the same sources as the app: the player for Now Playing, the books,
 * progress and downloads for Continue Listening. Each flow only emits when what a widget draws changes. With [scope]
 * the merged library (the costly part: every book, downloads and merge corrections) is computed once for all three
 * widgets and only while one of their flows is collected; its cache is dropped when nobody collects, so a later reader
 * never starts from an old list.
 */
class WidgetStates(
    private val controller: PlaybackController,
    private val books: BookRepository,
    private val downloads: DownloadStates,
    private val connectivity: ConnectivityMonitor,
    private val settings: AppSettings,
    private val merging: SourceMerging = SourceMerging.None,
    scope: CoroutineScope? = null,
) {
    private val freshLibrary: Flow<Library>
        get() = combine(observeBrowseItems(books, downloads, merging), connectivity.isOnline, settings.downloadedOnly, ::Library)

    private val library: Flow<Library> = scope?.let {
        freshLibrary.shareIn(it, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    } ?: freshLibrary

    /**
     * The loaded book's controls; with nothing loaded the last book with Play (the same book as [continueListening] and
     * [listen]), "Nothing played yet" only when there is no history.
     */
    val nowPlaying: Flow<NowPlayingWidgetState>
        get() = combine(controller.state, books.observeAllBooks(), library) { state, all, lib ->
            nowPlayingWidgetState(state, state.bookId?.let { id -> all.firstOrNull { it.id == id } }, lib.items, lib.online, lib.downloadedOnly)
        }.distinctUntilChanged()

    val continueListening: Flow<ContinueWidgetState>
        get() = library.map { continueWidgetState(it.items, it.online, it.downloadedOnly) }.distinctUntilChanged()

    /**
     * The Listen widget: the loaded book's controls, else the same book [continueListening] offers. Emits only when
     * that changes (a whole percent at most while playing, never a position tick).
     */
    val listen: Flow<ListenWidgetState>
        get() = combine(controller.state, books.observeAllBooks(), library) { state, all, lib ->
            val loaded = state.bookId?.let { id -> all.firstOrNull { it.id == id } }
            listenWidgetState(state, loaded, lib.items, lib.online, lib.downloadedOnly)
        }.distinctUntilChanged()

    /** Whether the phone is online (the Listen cover is asked for again when it comes back). */
    val online: Flow<Boolean>
        get() = connectivity.isOnline.distinctUntilChanged()

    /** Whether a Resume tap may start [bookId] now (see [widgetMayResume]); read fresh, never from a shared cache. */
    suspend fun mayResume(bookId: String): Boolean {
        val lib = freshLibrary.first()
        return widgetMayResume(lib.items, lib.online, lib.downloadedOnly, bookId)
    }

    /** What a widget's Play on [bookId] does now, against the book the player holds (see [widgetPlayDecision]). */
    suspend fun playDecision(bookId: String): WidgetPlayDecision {
        val loaded = controller.state.value.bookId ?: return WidgetPlayDecision.Start
        return widgetPlayDecision(loaded, bookId, observeBrowseItems(books, downloads, merging).first())
    }
}
