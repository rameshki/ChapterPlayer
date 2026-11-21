package com.chapterplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.backup.BackupService
import com.chapterplayer.data.backup.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Back up and restore page shows. */
data class BackupUiState(
    val busy: Boolean = false,
    /** The last result in words (an import's summary or why it did not happen); null before any. */
    val message: String? = null,
)

/** Settings, Back up and restore (review R27): builds the backup text and reads one back. */
class BackupViewModel(private val service: BackupService) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** Builds the backup text and hands it to [onReady]; says so on the page when it cannot be built. */
    fun export(onReady: (String) -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val text = try {
                service.export()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            _state.update { it.copy(busy = false, message = if (text == null) "The backup could not be made." else null) }
            if (text != null) onReady(text)
        }
    }

    /** Puts the pasted backup [text] back and shows what happened. */
    fun restore(text: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                when (val result = service.restore(text)) {
                    is RestoreResult.Done -> result.summary
                    is RestoreResult.Refused -> result.reason
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "The backup could not be restored."
            }
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
