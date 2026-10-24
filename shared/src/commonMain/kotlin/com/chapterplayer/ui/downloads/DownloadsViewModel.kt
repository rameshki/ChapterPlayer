package com.chapterplayer.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.storageHeaderText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the Downloads tab. [book] is null when the book has left the library. */
data class DownloadItem(
    val bookId: String,
    val book: AudioBook?,
    val title: String,
    val author: String,
    val status: DownloadStatus,
    /** 0 to 100. */
    val percent: Int,
    val statusLine: String,
    /** A failed download's reason; null otherwise. */
    val error: String?,
)

/** The download waiting for "Remove" to be confirmed. */
data class RemoveRequest(val bookId: String, val title: String)

data class DownloadsUiState(
    val loading: Boolean = true,
    val items: List<DownloadItem> = emptyList(),
    /** "1.2 GB used · Free: 30 GB"; null until known. */
    val headerText: String? = null,
    val confirmRemove: RemoveRequest? = null,
)

/** The Downloads tab: every download with its state and buttons, and the storage they use. */
class DownloadsViewModel(
    overview: DownloadOverview,
    books: BookRepository,
    private val controller: DownloadController,
    private val storage: DownloadStorage,
    dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val confirm = MutableStateFlow<String?>(null)

    private val items = combine(overview.observe(), books.observeAllBooks()) { rows, all ->
        val byId = all.associateBy { it.id }
        sortDownloadItems(
            rows.map { row ->
                val book = byId[row.bookId]
                DownloadItem(
                    bookId = row.bookId,
                    book = book,
                    title = book?.title ?: MISSING_BOOK_TITLE,
                    author = book?.author.orEmpty(),
                    status = row.status,
                    percent = (row.fraction * 100).toInt().coerceIn(0, 100),
                    statusLine = downloadStatusLine(row),
                    error = row.error,
                )
            }
        )
    }.flowOn(dispatcher)

    // The free space is read again whenever the downloads' size changes.
    private val header = overview.storageUsedBytes()
        .map { used -> storageHeaderText(used, freeBytesOrZero()) }
        .flowOn(dispatcher)

    val uiState: StateFlow<DownloadsUiState> = combine(items, header, confirm) { items, header, confirming ->
        DownloadsUiState(
            loading = false,
            items = items,
            headerText = header,
            confirmRemove = confirming?.let { id -> RemoveRequest(id, items.firstOrNull { it.bookId == id }?.title ?: MISSING_BOOK_TITLE) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun pause(bookId: String) = act { controller.pause(bookId) }

    /** Resume after a pause, or Try again after a failure. */
    fun resume(bookId: String) = act { controller.resume(bookId) }

    fun cancel(bookId: String) = act { controller.cancel(bookId) }

    fun askRemove(bookId: String) {
        confirm.value = bookId
    }

    fun dismissRemove() {
        confirm.value = null
    }

    fun confirmRemove() {
        val bookId = confirm.value ?: return
        confirm.value = null
        act { controller.remove(bookId) }
    }

    private suspend fun freeBytesOrZero(): Long = try {
        storage.freeBytes()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        0L
    }

    // The list follows the tables, so a failed call simply leaves the row as it was.
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}
