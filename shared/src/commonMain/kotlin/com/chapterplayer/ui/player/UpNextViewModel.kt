package com.chapterplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.upnext.UpNextRepository
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.formatLength
import com.chapterplayer.domain.playback.moveInUpNext
import com.chapterplayer.domain.playback.removeFromUpNext
import com.chapterplayer.domain.playback.resolveUpNext
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the Up next sheet: the queued entry as the lists show it. */
data class UpNextRow(
    /** The stored row (move and remove act on it). */
    val queueId: String,
    val book: AudioBook,
    /** Every copy's id: removing the row removes the whole entry. */
    val copyIds: List<String>,
    /** "8 h 12 min"; empty when the length is not known. */
    val lengthText: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class UpNextUiState(val rows: List<UpNextRow> = emptyList(), val loading: Boolean = true)

/** The rows of the sheet (and of Home's shelf) from the stored [queue] and the merged list; gone books are left out. */
fun upNextRows(queue: List<String>, items: List<BrowseItem>): List<UpNextRow> {
    val entries = resolveUpNext(queue, items).entries
    return entries.mapIndexed { index, row ->
        val entry = row.entry
        // A copy whose length is not known yet (a Plex album) shows another copy's.
        val length = entry.allSources.firstOrNull { it.book.durationMs > 0L }?.book?.durationMs ?: 0L
        UpNextRow(
            queueId = row.queueId,
            book = entry.book,
            copyIds = entry.allSources.map { it.book.id }.distinct(),
            lengthText = if (length > 0L) formatLength(length) else "",
            canMoveUp = index > 0,
            canMoveDown = index < entries.lastIndex,
        )
    }
}

/** The Up next sheet of the player: the queue in order, remove a row, move it up or down, clear it all. */
class UpNextViewModel(
    books: BookRepository,
    downloads: DownloadStates,
    private val upNext: UpNextRepository,
    dispatcher: CoroutineDispatcher,
    merging: SourceMerging = SourceMerging.None,
) : ViewModel() {

    val uiState: StateFlow<UpNextUiState> = combine(observeBrowseItems(books, downloads, merging), upNext.queue) { items, queue ->
        UpNextUiState(upNextRows(queue, items), loading = false)
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpNextUiState())

    fun onRemove(queueId: String) {
        val copies = uiState.value.rows.firstOrNull { it.queueId == queueId }?.copyIds.orEmpty()
        change { removeFromUpNext(it, copies + queueId) }
    }

    fun onMoveUp(queueId: String) = move(queueId, -1)

    fun onMoveDown(queueId: String) = move(queueId, 1)

    fun onClearAll() = change { emptyList() }

    /*
     * The stored queue can hold rows the sheet does not show (a book that is gone, a second row of one entry), so a
     * move steps over them: it swaps with the next shown row, never with a hidden one.
     */
    private fun move(queueId: String, by: Int) {
        val shown = uiState.value.rows.map { it.queueId }
        val at = shown.indexOf(queueId)
        val target = shown.getOrNull(at + by) ?: return
        change { queue ->
            val from = queue.indexOf(queueId)
            val to = queue.indexOf(target)
            if (from < 0 || to < 0) queue else moveInUpNext(queue, queueId, to - from)
        }
    }

    private fun change(edit: (List<String>) -> List<String>) {
        viewModelScope.launch {
            try {
                upNext.edit(edit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The sheet keeps showing the stored queue; the person can try again.
            }
        }
    }
}
