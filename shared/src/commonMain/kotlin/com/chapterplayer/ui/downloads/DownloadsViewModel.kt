package com.chapterplayer.ui.downloads

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.storageHeaderText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the Downloads tab; only what the row draws. [coverUrl] and [author] are empty when the book left the library. */
@Immutable
data class DownloadItem(
    val bookId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val status: DownloadStatus,
    /** 0 to 100. */
    val percent: Int,
    val statusLine: String,
    /** A failed download's reason; null otherwise. */
    val error: String?,
    /** Try again needs a connection, like on Book details. */
    val canRetry: Boolean = true,
    /** The book is still in the library, so the row opens its details. */
    val opensDetails: Boolean = true,
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

/**
 * The Downloads tab: every download with its state and buttons, and the storage they use. The free space is read
 * again at most every [freeSpaceEveryMs] while the downloads grow (the used figure follows every change).
 */
class DownloadsViewModel(
    overview: DownloadOverview,
    books: BookRepository,
    private val controller: DownloadController,
    private val storage: DownloadStorage,
    connectivity: ConnectivityMonitor,
    dispatcher: CoroutineDispatcher,
    private val freeSpaceEveryMs: Long = 5_000,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    private data class BookInfo(val title: String, val author: String, val coverUrl: String?)

    private val confirm = MutableStateFlow<String?>(null)
    private val online = connectivity.isOnline.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Only what the rows need, keyed by id, so a change elsewhere in the library list does not rebuild the rows.
    private val bookInfo = books.observeAllBooks()
        .map { all -> all.associate { it.id to BookInfo(it.title, it.author, it.coverUrl) } }
        .distinctUntilChanged()
        .flowOn(dispatcher)

    private val items = combine(overview.observe(), bookInfo, online) { rows, byId, isOnline ->
        sortDownloadItems(
            rows.map { row ->
                val book = byId[row.bookId]
                DownloadItem(
                    bookId = row.bookId,
                    title = book?.title ?: MISSING_BOOK_TITLE,
                    author = book?.author.orEmpty(),
                    coverUrl = book?.coverUrl,
                    status = row.status,
                    percent = (row.fraction * 100).toInt().coerceIn(0, 100),
                    statusLine = downloadStatusLine(row),
                    error = row.error,
                    canRetry = isOnline,
                    opensDetails = book != null,
                )
            }
        )
    }.flowOn(dispatcher)

    private var lastFreeAtMs: Long? = null
    private var lastFree = 0L

    private val header = overview.storageUsedBytes()
        .map { used -> storageHeaderText(used, freeBytes()) }
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

    fun resume(bookId: String) = act { controller.resume(bookId) }

    /** Try again after a failure; needs a connection. */
    fun retry(bookId: String) {
        if (!online.value) return
        act { controller.resume(bookId) }
    }

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

    private suspend fun freeBytes(): Long {
        val now = nowMs()
        val last = lastFreeAtMs
        if (last != null && now - last < freeSpaceEveryMs) return lastFree
        lastFree = try {
            storage.freeBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            lastFree
        }
        lastFreeAtMs = now
        return lastFree
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
