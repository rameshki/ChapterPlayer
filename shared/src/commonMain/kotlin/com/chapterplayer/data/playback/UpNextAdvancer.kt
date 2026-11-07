package com.chapterplayer.data.playback

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.upnext.UpNextRepository
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.playback.endedWhilePlaying
import com.chapterplayer.domain.playback.planUpNext
import com.chapterplayer.domain.playback.removeFromUpNext
import com.chapterplayer.domain.playback.upNextCanPlay
import com.chapterplayer.domain.playback.upNextRowsOf
import com.chapterplayer.domain.playback.upNextSkipText
import com.chapterplayer.domain.playback.upNextStartFailedText
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Short notes of what Up next did without being asked (a book skipped, a start that failed), for a snackbar over
 * whatever screen is open. Nothing is kept: a note nobody is shown is dropped.
 */
class UpNextNotices {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun post(text: String) {
        _messages.tryEmit(text)
    }
}

/**
 * Plays "Up next": when the loaded book plays to its very end ([endedWhilePlaying]: not a pause, a stop, a switch of
 * book or the sleep timer), the first queued entry that can play starts through [PlaybackStarter] (the one load path,
 * so the offline and "Downloaded only" rules apply), with the copy chosen as the car does it ([planUpNext]: a
 * downloaded copy first, else the copy played last; nothing asks, the phone may be in a pocket). Entries that cannot
 * play now are skipped with a note and stay queued; a start that fails keeps its entry, shows the reason, and nothing
 * is tried again until another book ends. Any book that starts playing, from anywhere (the app, the car, a widget, the
 * advance itself), leaves the queue. App-scoped: started by the Android application and by iOS's `initKoin`.
 */
class UpNextAdvancer(
    private val controller: PlaybackController,
    private val upNext: UpNextRepository,
    private val books: BookRepository,
    private val downloads: DownloadStates,
    private val connectivity: ConnectivityMonitor,
    private val settings: AppSettings,
    private val starter: PlaybackStarter,
    private val scope: CoroutineScope,
    private val notices: UpNextNotices = UpNextNotices(),
    private val merging: SourceMerging = SourceMerging.None,
    /** Where the state is followed and the player called (the Android controller wants the main thread). */
    private val main: CoroutineContext = Dispatchers.Main,
) {
    private var started = false
    private var previous: PlaybackState? = null
    // The loaded book that was already taken off the queue when it started playing.
    private var claimed: String? = null
    private var advancing: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch(main) {
            controller.state.collect { state ->
                try {
                    handle(state)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failure here must not end the advancer.
                }
            }
        }
    }

    private fun handle(state: PlaybackState) {
        val bookId = state.bookId
        if (bookId != null && bookId != claimed && state.isPlaying && state.error == null) {
            claimed = bookId
            scope.launch(main) { guarded { removeEntryOf(bookId) } }
        }
        if (endedWhilePlaying(previous, state) && advancing?.isActive != true) {
            advancing = scope.launch(main) { guarded { advanceAfter(bookId!!) } }
        }
        previous = state
    }

    private suspend fun advanceAfter(endedBookId: String) {
        val queue = upNext.queue.first()
        if (queue.isEmpty()) return
        val items = observeBrowseItems(books, downloads, merging).first()
        val reachable = merging.context.first()?.onlineServerIds
        val online = connectivity.isOnline.first()
        val downloadedOnly = settings.downloadedOnly.first()
        val plan = planUpNext(queue, items, endedBookId) { member -> upNextCanPlay(member, online, downloadedOnly, reachable) }
        if (plan.drop.isNotEmpty()) upNext.edit { removeFromUpNext(it, plan.drop) }
        upNextSkipText(plan.skipped.map { it.entry.book.title }, started = plan.start != null)?.let(notices::post)
        val pick = plan.start ?: return
        val result = starter.start(pick.copy.book)
        result.onSuccess {
            claimed = pick.copy.book.id
            removeEntry(items, pick.copy.book.id)
        }.onFailure { failure ->
            notices.post(upNextStartFailedText(failure.message ?: PlaybackStartException.DEFAULT_MESSAGE))
        }
    }

    private suspend fun removeEntryOf(bookId: String) {
        if (upNext.queue.first().isEmpty()) return
        removeEntry(observeBrowseItems(books, downloads, merging).first(), bookId)
    }

    private suspend fun removeEntry(items: List<BrowseItem>, bookId: String) {
        upNext.edit { queue -> removeFromUpNext(queue, upNextRowsOf(queue, items, bookId)) }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The queue stays as it was; the next end or start tries again.
        }
    }
}
