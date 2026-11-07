package com.chapterplayer.data.playback

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.upnext.UpNextRepository
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.domain.playback.carriedSleepTimer
import com.chapterplayer.domain.playback.sleepLeftAtEnd
import com.chapterplayer.domain.playback.upNextSkipReason
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
import kotlin.time.Clock

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
 * book, a skip onto the end or a sleep timer due at the end), the first queued entry that can play starts through
 * [PlaybackStarter] (the one load path, so the offline and "Downloaded only" rules apply), with the copy chosen as the
 * car does it ([planUpNext]: a downloaded copy first, else the copy played last; nothing asks, the phone may be in a
 * pocket). Entries that cannot play now are skipped with a note naming why and stay queued; a start that fails keeps
 * its entry, shows the reason, and nothing is tried again until another book ends. Any book that starts playing, from
 * anywhere (the app, the car, a widget, the advance itself), leaves the queue.
 *
 * The advance gives way to the person: when another book is loaded meanwhile, or the ended book is played or moved
 * again, it is cancelled, and right before the start it checks once more that the player still stands at the end of
 * the ended book. A running "After" sleep timer carries its time left over to the next book ([carriedSleepTimer]), so
 * it still stops playback when it runs out. The advance runs inside [background] work (iOS: a UIKit background task,
 * since nothing plays while it fetches). App-scoped: started by the Android application and by iOS's `initKoin`.
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
    private val background: BackgroundWork = BackgroundWork.None,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var started = false
    private var previous: PlaybackState? = null
    // The loaded book already taken off the queue when it started playing; forgotten when another (or no) book is loaded.
    private var claimed: String? = null
    private var advancing: Job? = null
    // The book the running advance moves on from, and the one it is starting (null until it starts one).
    private var advancingFrom: String? = null
    private var startingId: String? = null

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
        if (bookId != claimed) claimed = null
        if (bookId != null && claimed == null && state.isPlaying && state.error == null) {
            claimed = bookId
            scope.launch(main) { guarded { removeEntryOf(bookId) } }
        }
        if (advancing?.isActive == true && !advanceStillWanted(state)) advancing?.cancel()
        val before = previous
        if (endedWhilePlaying(before, state) && advancing?.isActive != true) {
            val endedId = bookId!!
            // Read now: the timer and its time left as the book ended.
            val timer = controller.sleepTimer.value
            val leftAtEnd = before?.let(::sleepLeftAtEnd)
            val endedAt = nowMs()
            advancingFrom = endedId
            startingId = null
            advancing = scope.launch(main) {
                val job = coroutineContext[Job]
                val end = background.begin(onExpired = { job?.cancel() })
                try {
                    guarded { advanceAfter(endedId, timer, leftAtEnd, endedAt) }
                } finally {
                    end()
                }
            }
        }
        previous = state
    }

    /** Whether the running advance still has the player to itself: still at the end of the ended book, or loading its pick. */
    private fun advanceStillWanted(state: PlaybackState): Boolean {
        val id = state.bookId
        val starting = startingId
        return when {
            id == null -> starting != null
            id == advancingFrom -> state.ended
            else -> id == starting
        }
    }

    private suspend fun advanceAfter(endedBookId: String, timer: SleepTimer, sleepLeftAtEnd: Long?, endedAt: Long) {
        val queue = upNext.queue.first()
        if (queue.isEmpty()) return
        val items = observeBrowseItems(books, downloads, merging).first()
        val reachable = merging.context.first()?.onlineServerIds
        val online = connectivity.isOnline.first()
        val downloadedOnly = settings.downloadedOnly.first()
        val plan = planUpNext(queue, items, endedBookId) { member -> upNextCanPlay(member, online, downloadedOnly, reachable) }
        if (plan.drop.isNotEmpty()) upNext.edit { removeFromUpNext(it, plan.drop) }
        val reason = upNextSkipReason(online, downloadedOnly)
        upNextSkipText(plan.skipped.map { it.entry.book.title }, started = plan.start != null, reason = reason)?.let(notices::post)
        val pick = plan.start ?: return
        // The person may have picked or moved something in the meantime: then this advance is not wanted.
        if (controller.state.value.let { it.bookId != endedBookId || !it.ended }) return
        startingId = pick.copy.book.id
        val result = starter.start(pick.copy.book)
        result.onSuccess {
            claimed = pick.copy.book.id
            carriedSleepTimer(timer, sleepLeftAtEnd, nowMs() - endedAt)?.let(controller::setSleepTimer)
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
