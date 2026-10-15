package com.chapterplayer.ui.library

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.ui.components.LibraryFilter

/**
 * Complete UI state for the Library screen.
 * The ViewModel emits a single [LibraryUiState] object that the screen observes.
 */
data class LibraryUiState(
    val allBooks: List<AudioBook> = emptyList(),
    val inProgressBooks: List<AudioBook> = emptyList(),
    val progressMap: Map<String, ReadingProgress> = emptyMap(),
    val activeFilter: LibraryFilter = LibraryFilter.ALL,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val hasServersConfigured: Boolean = false,
    /** Display names of servers whose sign-in is gone (their stored token is blank). */
    val signInRequiredNames: List<String> = emptyList(),
    val allServersNeedSignIn: Boolean = false
) {
    /** Books to display in the grid, filtered by [activeFilter]. */
    val filteredBooks: List<AudioBook>
        get() = when (activeFilter) {
            LibraryFilter.ALL -> allBooks
            LibraryFilter.ABS -> allBooks.filter {
                it.source == com.chapterplayer.data.model.ServerSource.ABS
            }
            LibraryFilter.PLEX -> allBooks.filter {
                it.source == com.chapterplayer.data.model.ServerSource.PLEX
            }
        }

    val isEmpty: Boolean get() = allBooks.isEmpty() && !isSyncing
    val showContinueReading: Boolean get() = inProgressBooks.isNotEmpty()
}
