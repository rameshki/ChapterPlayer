package com.chapterplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SettingsUiState(
    val servers: List<ServerConfig> = emptyList()
)

data class ServerSetupUiState(
    val name: String = "",
    val source: ServerSource = ServerSource.ABS,
    val baseUrl: String = "",
    val token: String = "",
    val libraryId: String = "",
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val savedSuccessfully: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverRepository: ServerRepository
) : ViewModel() {

    val settingsUiState: StateFlow<SettingsUiState> =
        serverRepository.observeAll()
            .map { SettingsUiState(servers = it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SettingsUiState()
            )

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            serverRepository.delete(serverId)
        }
    }
}

@HiltViewModel
class ServerSetupViewModel @Inject constructor(
    private val serverRepository: ServerRepository
) : ViewModel() {

    private var _uiState = kotlinx.coroutines.flow.MutableStateFlow(ServerSetupUiState())
    val uiState: StateFlow<ServerSetupUiState> = _uiState

    /** Load existing server config for editing. */
    fun loadServer(serverId: String) {
        viewModelScope.launch {
            val config = serverRepository.getById(serverId) ?: return@launch
            _uiState.value = ServerSetupUiState(
                name = config.name,
                source = config.source,
                baseUrl = config.baseUrl,
                token = config.token,
                libraryId = config.libraryId ?: ""
            )
        }
    }

    fun onNameChanged(name: String) = _uiState.value.let { _uiState.value = it.copy(name = name) }
    fun onSourceChanged(source: ServerSource) = _uiState.value.let { _uiState.value = it.copy(source = source) }
    fun onBaseUrlChanged(url: String) = _uiState.value.let { _uiState.value = it.copy(baseUrl = url) }
    fun onTokenChanged(token: String) = _uiState.value.let { _uiState.value = it.copy(token = token) }
    fun onLibraryIdChanged(id: String) = _uiState.value.let { _uiState.value = it.copy(libraryId = id) }

    fun saveServer(existingId: String?) {
        val state = _uiState.value
        if (state.name.isBlank() || state.baseUrl.isBlank() || state.token.isBlank()) {
            _uiState.value = state.copy(saveError = "Name, URL, and token are required.")
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, saveError = null)
            val config = ServerConfig(
                id = existingId ?: UUID.randomUUID().toString(),
                source = state.source,
                name = state.name.trim(),
                baseUrl = state.baseUrl.trimEnd('/'),
                token = state.token.trim(),
                libraryId = state.libraryId.takeIf { it.isNotBlank() }
            )
            serverRepository.save(config)
            _uiState.value = _uiState.value.copy(isSaving = false, savedSuccessfully = true)
        }
    }

    fun onErrorDismissed() {
        _uiState.value = _uiState.value.copy(saveError = null)
    }
}
