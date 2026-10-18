package com.chapterplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.repository.LibraryCatalog
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.sync.LibrarySync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Libraries check-list. Each tick writes the choice first and then asks for a sync of that server,
 * because turning a library off only removes books that carry its id, and a sync already running could
 * bring them back. Toggles of one server run one after another so none is lost.
 */
class LibrariesViewModel(
    catalog: LibraryCatalog,
    private val servers: ServerRepository,
    private val sync: LibrarySync
) : ViewModel() {

    private val toggleError = MutableStateFlow<String?>(null)
    private val touched = MutableStateFlow(false)
    private val locks = mutableMapOf<String, Mutex>()

    val uiState: StateFlow<LibrariesUiState> = combine(
        catalog.observeServerLibraries(), sync.state, toggleError, touched
    ) { catalogServers, syncState, failure, wasTouched ->
        val sections = catalogServers.map { it.toSection() }
        val rows = sections.flatMap { it.libraries }
        LibrariesUiState(
            servers = sections,
            everythingHidden = rows.isNotEmpty() && rows.none { it.checked },
            isSyncing = wasTouched && syncState.isSyncing,
            error = failure ?: syncState.syncError.takeIf { wasTouched }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrariesUiState())

    fun onToggle(serverId: String, libraryId: String, enabled: Boolean) {
        touched.value = true
        toggleError.value = null
        val lock = locks.getOrPut(serverId) { Mutex() }
        viewModelScope.launch {
            lock.withLock {
                try {
                    servers.setLibraryEnabled(serverId, libraryId, enabled)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toggleError.value = "Could not change the library: ${e.message ?: "Unknown error"}"
                    return@launch
                }
                sync.requestSync(serverId)
            }
        }
    }
}
