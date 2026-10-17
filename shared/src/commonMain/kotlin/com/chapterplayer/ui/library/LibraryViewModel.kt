package com.chapterplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.ui.components.LibraryFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val bookRepository: BookRepository,
    private val serverRepository: ServerRepository
) : ViewModel() {

    // Internal mutable state for things the UI can change (filter, sync status)
    private val _syncState = MutableStateFlow(SyncState())
    private val _activeFilter = MutableStateFlow(LibraryFilter.ALL)

    init {
        // Auto-sync any configured server that has nothing in the local cache yet
        // (first launch, or a server was just added), so the user doesn't have to
        // tap refresh manually.
        viewModelScope.launch {
            serverRepository.observeAll()
                .distinctUntilChangedBy { servers -> servers.map { it.id }.toSet() }
                .collect { servers ->
                    val cachedServerIds = bookRepository.observeAllBooks().first()
                        .mapTo(mutableSetOf()) { it.serverId }
                    val uncached = servers.filter { it.id !in cachedServerIds }
                    if (uncached.isNotEmpty()) syncServers(uncached)
                }
        }
    }

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
            hasServersConfigured = servers.isNotEmpty(),
            signInRequiredNames = servers.filter { it.needsSignIn }.map { it.name },
            allServersNeedSignIn = servers.isNotEmpty() && servers.all { it.needsSignIn }
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
            syncServers(serverRepository.observeAll().first())
        }
    }

    private suspend fun syncServers(servers: List<ServerConfig>) {
        _syncState.update { it.copy(isSyncing = true, error = null) }

        val errors = mutableListOf<String>()
        servers.forEach { server ->
            if (server.needsSignIn) {
                // The stored sign-in is gone, so there is nothing to send to the server.
                errors.add(signInAgainMessage(server))
                return@forEach
            }
            bookRepository.syncBooksFromServer(server)
                .onFailure { throwable ->
                    errors.add(
                        if (throwable is SignInRequiredException) signInAgainMessage(server)
                        else "${server.name}: ${throwable.message ?: "Unknown error"}"
                    )
                }
        }

        _syncState.update {
            it.copy(
                isSyncing = false,
                error = if (errors.isNotEmpty()) errors.joinToString("\n") else null
            )
        }
    }

    private fun signInAgainMessage(server: ServerConfig) = "${server.name}: Sign in again in Settings"

    fun onErrorDismissed() {
        _syncState.update { it.copy(error = null) }
    }

    private data class SyncState(
        val isSyncing: Boolean = false,
        val error: String? = null
    )
}
