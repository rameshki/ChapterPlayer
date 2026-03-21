package com.chapterplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.ui.components.LibraryFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val serverRepository: ServerRepository
) : ViewModel() {

    // Internal mutable state for things the UI can change (filter, sync status)
    private val _syncState = MutableStateFlow(SyncState())
    private val _activeFilter = MutableStateFlow(LibraryFilter.ALL)

    // Exposed as StateFlow — the screen collects this
    val uiState: StateFlow<LibraryUiState> = combine(
        bookRepository.observeAllBooks(),
        bookRepository.observeInProgressBooks(),
        serverRepository.observeAll(),
        _activeFilter,
        _syncState
    ) { books, inProgressEntries, servers, filter, syncState ->

        // Build a map of bookId → ReadingProgress for quick lookup in the UI
        val progressMap = inProgressEntries.associateBy { it.bookId }

        // In-progress books in library order (only books we have cached)
        val bookIndex = books.associateBy { it.id }
        val inProgressBooks = inProgressEntries
            .mapNotNull { bookIndex[it.bookId] }
            .sortedByDescending { progressMap[it.id]?.lastPlayedAt ?: 0L }

        LibraryUiState(
            allBooks = books.sortedBy { it.title },
            inProgressBooks = inProgressBooks,
            progressMap = progressMap,
            activeFilter = filter,
            isSyncing = syncState.isSyncing,
            syncError = syncState.error,
            hasServersConfigured = servers.isNotEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

    // ─── User actions ──────────────────────────────────────────────────────

    fun onFilterSelected(filter: LibraryFilter) {
        _activeFilter.value = filter
    }

    fun onRefreshRequested() {
        viewModelScope.launch {
            _syncState.update { it.copy(isSyncing = true, error = null) }

            val servers = serverRepository.observeAll().stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            ).value

            val errors = mutableListOf<String>()
            servers.forEach { server ->
                bookRepository.syncBooksFromServer(server)
                    .onFailure { throwable ->
                        errors.add("${server.name}: ${throwable.message ?: "Unknown error"}")
                    }
            }

            _syncState.update {
                it.copy(
                    isSyncing = false,
                    error = if (errors.isNotEmpty()) errors.joinToString("\n") else null
                )
            }
        }
    }

    fun onErrorDismissed() {
        _syncState.update { it.copy(error = null) }
    }

    private data class SyncState(
        val isSyncing: Boolean = false,
        val error: String? = null
    )
}
