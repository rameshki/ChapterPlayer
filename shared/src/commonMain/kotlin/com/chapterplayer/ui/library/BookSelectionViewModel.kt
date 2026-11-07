package com.chapterplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.favourites.FavouritesRepository
import com.chapterplayer.data.favourites.NoFavourites
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.permissions.NotificationPermission
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.upnext.NoUpNext
import com.chapterplayer.data.upnext.UpNextRepository
import com.chapterplayer.domain.browse.BULK_MARK_PARALLELISM
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BulkAction
import com.chapterplayer.domain.browse.BulkTally
import com.chapterplayer.domain.browse.BulkUpNextResult
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.addAllToUpNext
import com.chapterplayer.domain.browse.alreadyMarked
import com.chapterplayer.domain.browse.bulkDownloadPlan
import com.chapterplayer.domain.browse.bulkFavouriteAction
import com.chapterplayer.domain.browse.bulkProgressText
import com.chapterplayer.domain.browse.bulkRemovePlan
import com.chapterplayer.domain.browse.bulkResultText
import com.chapterplayer.domain.browse.favouriteRowIds
import com.chapterplayer.domain.browse.isFavourite
import com.chapterplayer.domain.browse.markTarget
import com.chapterplayer.domain.browse.pruneSelection
import com.chapterplayer.domain.browse.runBounded
import com.chapterplayer.domain.browse.selectAllOf
import com.chapterplayer.domain.browse.selectedEntries
import com.chapterplayer.domain.browse.selectionTitle
import com.chapterplayer.domain.browse.toggleSelected
import com.chapterplayer.domain.playback.MarkOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A bulk action under way: [done] of [total] books handled; [stopping] after Cancel. */
data class BulkProgressUi(val action: BulkAction, val done: Int, val total: Int, val stopping: Boolean = false) {
    val text: String get() = bulkProgressText(action, done, total, stopping)
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/**
 * Selection mode of one book list. [selected] holds the selected entries by primary id; [favouriteAction] is Add or
 * Remove from favourites as the selection decides; [confirmRemoveCount] is set while "Remove N downloads?" asks.
 */
data class SelectionUiState(
    val active: Boolean = false,
    val selected: Set<String> = emptySet(),
    val favouriteAction: BulkAction = BulkAction.ADD_FAVOURITES,
    val progress: BulkProgressUi? = null,
    val confirmRemoveCount: Int? = null,
    val online: Boolean = true,
) {
    val count: Int get() = selected.size
    val title: String get() = progress?.text ?: selectionTitle(count)
    val running: Boolean get() = progress != null
    val canAct: Boolean get() = count > 0 && !running
}

/**
 * The selection mode of a book list (Library and the group pages; one per screen). The screen hands in the list as
 * shown ([onItemsChanged]); selected entries that leave it leave the selection. A bulk action runs each book through
 * the same calls as the book's own menu ([BookRepository.markFinished] on [markTarget], the favourites and Up next
 * repositories over every copy, [DownloadController.enqueue] of the copy the "Download from" rule picks without asking,
 * [DownloadController.remove]), one book after another (marks: [BULK_MARK_PARALLELISM] at a time), on [dispatcher],
 * with its progress in the top bar and Cancel stopping further books. The result is one [message]; the mode then closes.
 */
class BookSelectionViewModel(
    private val books: BookRepository,
    private val downloadController: DownloadController,
    private val notifications: NotificationPermission,
    private val player: PlaybackController,
    connectivity: ConnectivityMonitor,
    private val dispatcher: CoroutineDispatcher,
    merging: SourceMerging = SourceMerging.None,
    private val favourites: FavouritesRepository = NoFavourites,
    private val upNext: UpNextRepository = NoUpNext,
) : ViewModel() {

    private class Mode(
        val active: Boolean = false,
        val selected: Set<String> = emptySet(),
        val progress: BulkProgressUi? = null,
        val confirmRemove: Int? = null,
    )

    private val mode = MutableStateFlow(Mode())
    private val visible = MutableStateFlow<List<BrowseItem>>(emptyList())
    private val stopRequested = MutableStateFlow(false)
    private val context: StateFlow<MergeContext?> = merging.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val favouriteIds: StateFlow<Set<String>> = favourites.favouriteIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val online: StateFlow<Boolean> = connectivity.isOnline.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _message = MutableStateFlow<String?>(null)

    /** The result of the last bulk action, for a snackbar; see [onMessageShown]. */
    val message: StateFlow<String?> = _message

    val uiState: StateFlow<SelectionUiState> = combine(mode, visible, favouriteIds, online) { mode, items, favs, isOnline ->
        SelectionUiState(
            active = mode.active,
            selected = mode.selected,
            // Worked out only while the menu can show (not on every progress step of a running action).
            favouriteAction = if (mode.active && mode.progress == null) bulkFavouriteAction(selectedEntries(items, mode.selected), favs)
            else BulkAction.ADD_FAVOURITES,
            progress = mode.progress,
            confirmRemoveCount = mode.confirmRemove,
            online = isOnline,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SelectionUiState())

    /** The list as the screen shows it now (filtered and sorted). */
    fun onItemsChanged(items: List<BrowseItem>) {
        visible.value = items
        mode.update { m -> if (m.selected.isEmpty()) m else Mode(m.active, pruneSelection(m.selected, items), m.progress, m.confirmRemove) }
    }

    /** Selection mode on, with [entryId] selected (the long-press menu's "Select") or nothing (the top bar's button). */
    fun start(entryId: String? = null) {
        mode.update { m ->
            if (m.progress != null) return@update m
            val selected = if (entryId == null) m.selected else pruneSelection(m.selected + entryId, visible.value)
            Mode(true, selected, null, null)
        }
    }

    fun toggle(entryId: String) {
        mode.update { m -> if (!m.active || m.progress != null) m else Mode(true, toggleSelected(m.selected, entryId), null, m.confirmRemove) }
    }

    /** Every entry of the list as shown. */
    fun selectAll() {
        mode.update { m -> if (!m.active || m.progress != null) m else Mode(true, selectAllOf(visible.value), null, m.confirmRemove) }
    }

    /** Close (or Back): leaves selection mode. While an action runs it is Cancel instead. */
    fun close() {
        if (mode.value.progress != null) {
            cancel()
            return
        }
        mode.value = Mode()
    }

    /** Stops a running action after the books under way; those done stay done. */
    fun cancel() {
        if (mode.value.progress == null) return
        stopRequested.value = true
        mode.update { m -> m.progress?.let { Mode(m.active, m.selected, it.copy(stopping = true), null) } ?: m }
    }

    fun onMessageShown() {
        _message.value = null
    }

    /** Runs [action] over the selection ("Remove download" asks first, see [confirmRemoveDownloads]). */
    fun run(action: BulkAction) {
        val m = mode.value
        if (!m.active || m.progress != null || m.selected.isEmpty()) return
        val entries = selectedEntries(visible.value, m.selected)
        if (entries.isEmpty()) return
        when (action) {
            BulkAction.REMOVE_DOWNLOAD -> {
                val count = bulkRemovePlan(entries).copyIds.size
                if (count == 0) finish(bulkResultText(action, BulkTally(already = entries.size)))
                else mode.value = Mode(true, m.selected, null, count)
            }
            else -> launchRun(action, entries)
        }
    }

    fun confirmRemoveDownloads() {
        val m = mode.value
        if (m.confirmRemove == null) return
        mode.value = Mode(m.active, m.selected, null, null)
        launchRun(BulkAction.REMOVE_DOWNLOAD, selectedEntries(visible.value, m.selected))
    }

    fun dismissRemoveDownloads() {
        val m = mode.value
        mode.value = Mode(m.active, m.selected, null, null)
    }

    private fun launchRun(action: BulkAction, entries: List<BrowseItem>) {
        stopRequested.value = false
        setProgress(BulkProgressUi(action, 0, entries.size))
        viewModelScope.launch(dispatcher) {
            val text = try {
                bulkResultText(action, perform(action, entries))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BULK_ERROR_TEXT
            }
            finish(text)
        }
    }

    private fun finish(text: String) {
        mode.value = Mode()
        _message.value = text
    }

    private fun setProgress(progress: BulkProgressUi) {
        mode.update { m -> Mode(m.active, m.selected, progress.copy(stopping = stopRequested.value), null) }
    }

    private fun stopped(): Boolean = stopRequested.value

    // Each book's outcome as the tally counts it.
    private enum class One { DONE, ALREADY, SKIPPED, QUEUED, PARTIAL, FAILED }

    private suspend fun perform(action: BulkAction, entries: List<BrowseItem>): BulkTally = when (action) {
        BulkAction.MARK_FINISHED -> runEach(action, entries, BULK_MARK_PARALLELISM) { mark(it, finished = true) }
        BulkAction.MARK_NOT_FINISHED -> runEach(action, entries, BULK_MARK_PARALLELISM) { mark(it, finished = false) }
        BulkAction.ADD_FAVOURITES, BulkAction.REMOVE_FAVOURITES -> {
            val adding = action == BulkAction.ADD_FAVOURITES
            val favs = favouriteIds.value
            runEach(action, entries, 1) { entry ->
                if (isFavourite(entry, favs) == adding) One.ALREADY
                else attempt {
                    if (adding) favourites.add(favouriteRowIds(entry)) else favourites.remove(favouriteRowIds(entry))
                }
            }
        }
        BulkAction.ADD_UP_NEXT -> {
            var result: BulkUpNextResult? = null
            val loaded = player.state.value.bookId
            upNext.edit { queue -> addAllToUpNext(queue, entries, loaded).also { result = it }.queue }
            setProgress(BulkProgressUi(action, entries.size, entries.size))
            val r = result ?: BulkUpNextResult(emptyList(), 0, 0, 0)
            BulkTally(done = r.added, already = r.alreadyQueued, skipped = r.playingNow, total = entries.size)
        }
        BulkAction.DOWNLOAD -> {
            val plan = bulkDownloadPlan(entries, context.value)
            if (plan.copyIds.isNotEmpty()) {
                try {
                    notifications.ensure()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No notification then; the downloads run anyway.
                }
            }
            val tally = runEach(action, plan.copyIds, 1) { id -> attempt { downloadController.enqueue(id) } }
            tally.copy(already = plan.alreadyOnPhone, skipped = plan.needsChoice)
        }
        BulkAction.REMOVE_DOWNLOAD -> {
            val plan = bulkRemovePlan(entries)
            val tally = runEach(action, plan.copyIds, 1) { id -> attempt { downloadController.remove(id) } }
            tally.copy(already = plan.notDownloaded)
        }
    }

    private suspend fun mark(entry: BrowseItem, finished: Boolean): One {
        if (alreadyMarked(entry, finished)) return One.ALREADY
        val result = try {
            books.markFinished(markTarget(entry, entry.book.id), finished)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure(IllegalStateException())
        }
        return result.fold(
            onSuccess = { outcome ->
                when (outcome) {
                    MarkOutcome.SYNCED, MarkOutcome.LOCAL_ONLY -> One.DONE
                    MarkOutcome.QUEUED -> One.QUEUED
                    MarkOutcome.PARTIAL, MarkOutcome.PARTIAL_QUEUED -> One.PARTIAL
                }
            },
            onFailure = { One.FAILED },
        )
    }

    private suspend fun attempt(block: suspend () -> Unit): One = try {
        block()
        One.DONE
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        One.FAILED
    }

    private suspend fun <T> runEach(action: BulkAction, items: List<T>, parallelism: Int, block: suspend (T) -> One): BulkTally {
        var handled = 0
        val results = runBounded(items, parallelism, ::stopped, onResult = { _, _ ->
            handled++
            setProgress(BulkProgressUi(action, handled, items.size))
        }, block = block)
        val ran = results.filterNotNull()
        // Queued and partial marks changed the book here; they count as done and say what is still to come.
        val done = ran.count { it == One.DONE || it == One.QUEUED || it == One.PARTIAL }
        return BulkTally(
            done = done,
            already = ran.count { it == One.ALREADY },
            skipped = ran.count { it == One.SKIPPED },
            queued = ran.count { it == One.QUEUED },
            partial = ran.count { it == One.PARTIAL },
            failed = ran.count { it == One.FAILED },
            stoppedAfter = if (ran.size < items.size) ran.size else null,
            total = items.size,
        )
    }

    private companion object {
        const val BULK_ERROR_TEXT = "Could not finish. Try again."
    }
}
