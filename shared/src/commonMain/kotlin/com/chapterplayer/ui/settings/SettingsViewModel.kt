@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.chapterplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

// ─────────────────────────────────────────────────────────────────────────────
// Settings screen state
// ─────────────────────────────────────────────────────────────────────────────

data class SettingsUiState(
    val servers: List<ServerConfig> = emptyList()
)

// ─────────────────────────────────────────────────────────────────────────────
// Server setup state
// ─────────────────────────────────────────────────────────────────────────────

/** Represents a Plex Media Server the user can connect to, discovered via plex.tv. */
data class PlexServerOption(
    val name: String,
    val url: String,
    val accessToken: String
)

/** Tracks which step of the Plex OAuth flow we're in. */
enum class PlexOAuthStep {
    IDLE,           // Not started — show "Sign in with Plex" button
    AWAITING_AUTH,  // Browser opened, polling for token
    PICKING_SERVER  // Token received, showing server picker dialog
}

data class ServerSetupUiState(
    // Common fields
    val name: String = "",
    val source: ServerSource = ServerSource.ABS,
    val libraryId: String = "",
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val savedSuccessfully: Boolean = false,

    // ABS-only fields
    val baseUrl: String = "",
    val token: String = "",

    // Plex OAuth fields
    val plexOAuthStep: PlexOAuthStep = PlexOAuthStep.IDLE,
    val plexPinId: Long? = null,
    val plexPinCode: String? = null,       // Set when browser should open; cleared after auth
    val plexClientId: String = "",
    val availablePlexServers: List<PlexServerOption> = emptyList(),
    val plexOAuthError: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Settings ViewModel (server list + delete)
// ─────────────────────────────────────────────────────────────────────────────

class SettingsViewModel(
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
            try {
                serverRepository.delete(serverId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The row stays visible so the user can try again.
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server Setup ViewModel (add / edit, including Plex OAuth)
// ─────────────────────────────────────────────────────────────────────────────

private const val PLEX_PRODUCT = "ChapterPlayer"
private const val PLEX_VERSION = "0.1.0"
private const val POLL_INTERVAL_MS = 2_000L
private const val POLL_MAX_ATTEMPTS = 150   // ~5 minutes

class ServerSetupViewModel(
    private val serverRepository: ServerRepository,
    private val plexTvApiService: PlexTvApiService,
    private val plexClientIdentifier: PlexClientIdentifier
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSetupUiState(plexClientId = plexClientIdentifier.value))
    val uiState: StateFlow<ServerSetupUiState> = _uiState

    private var pollingJob: Job? = null

    // ── Load existing server (edit mode) ─────────────────────────────────────

    fun loadServer(serverId: String) {
        viewModelScope.launch {
            val config = serverRepository.getById(serverId) ?: return@launch
            _uiState.value = ServerSetupUiState(
                name = config.name,
                source = config.source,
                baseUrl = config.baseUrl,
                token = config.token,
                libraryId = config.libraryId ?: "",
                plexClientId = plexClientIdentifier.value
            )
        }
    }

    // ── Form field updates ────────────────────────────────────────────────────

    fun onNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun onSourceChanged(source: ServerSource) {
        // Cancel any in-flight OAuth when switching away from Plex
        if (source != ServerSource.PLEX) cancelPlexOAuth()
        _uiState.value = _uiState.value.copy(
            source = source,
            baseUrl = "",
            token = "",
            saveError = null
        )
    }

    fun onBaseUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(baseUrl = url)
    }

    fun onTokenChanged(token: String) {
        _uiState.value = _uiState.value.copy(token = token)
    }

    fun onLibraryIdChanged(id: String) {
        _uiState.value = _uiState.value.copy(libraryId = id)
    }

    fun onErrorDismissed() {
        _uiState.value = _uiState.value.copy(saveError = null, plexOAuthError = null)
    }

    // ── Plex OAuth flow ───────────────────────────────────────────────────────

    /**
     * Step 1: Create a pin on plex.tv and expose the pin code so the UI can
     * open the auth URL in the user's browser.
     */
    fun startPlexOAuth() {
        viewModelScope.launch {
            val clientId = _uiState.value.plexClientId
            try {
                val pin = plexTvApiService.createPin(
                    product = PLEX_PRODUCT,
                    version = PLEX_VERSION,
                    clientId = clientId
                )
                _uiState.value = _uiState.value.copy(
                    plexOAuthStep = PlexOAuthStep.AWAITING_AUTH,
                    plexPinId = pin.id,
                    plexPinCode = pin.code,
                    plexOAuthError = null
                )
                startPolling(pin.id, clientId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    plexOAuthError = "Couldn't reach plex.tv — check your internet connection."
                )
            }
        }
    }

    /**
     * Step 2: Poll plex.tv every 2s until the user completes sign-in and
     * authToken appears, then move to server discovery.
     */
    private fun startPolling(pinId: Long, clientId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            repeat(POLL_MAX_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                try {
                    val pin = plexTvApiService.checkPin(
                        pinId = pinId,
                        product = PLEX_PRODUCT,
                        version = PLEX_VERSION,
                        clientId = clientId
                    )
                    if (pin.authToken != null) {
                        fetchPlexServers(pin.authToken, clientId)
                        return@launch
                    }
                } catch (_: Exception) {
                    // Transient errors are fine — keep polling
                }
            }
            // Timeout
            _uiState.value = _uiState.value.copy(
                plexOAuthStep = PlexOAuthStep.IDLE,
                plexPinCode = null,
                plexPinId = null,
                plexOAuthError = "Sign-in timed out. Please try again."
            )
        }
    }

    /**
     * Step 3: Once we have a token, fetch the user's Plex servers so they
     * can pick which one to connect ChapterPlayer to.
     */
    private suspend fun fetchPlexServers(token: String, clientId: String) {
        try {
            val resources = plexTvApiService.getResources(
                token = token,
                product = PLEX_PRODUCT,
                version = PLEX_VERSION,
                clientId = clientId
            )
            val servers = resources
                .filter { it.provides.contains("server") }
                .mapNotNull { resource ->
                    // Prefer a local connection; fall back to relay/remote
                    val bestUrl = resource.connections
                        .firstOrNull { it.local }?.uri
                        ?: resource.connections.firstOrNull()?.uri
                        ?: return@mapNotNull null
                    PlexServerOption(
                        name = resource.name,
                        url = bestUrl,
                        // Shared servers reject the account token; use the server's own token
                        accessToken = resource.accessToken ?: token
                    )
                }

            if (servers.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    plexOAuthStep = PlexOAuthStep.IDLE,
                    plexOAuthError = "Signed in, but no Plex Media Servers found on your account."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    token = token,
                    plexOAuthStep = PlexOAuthStep.PICKING_SERVER,
                    availablePlexServers = servers,
                    plexOAuthError = null
                )
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                plexOAuthStep = PlexOAuthStep.IDLE,
                plexOAuthError = "Signed in, but couldn't fetch your servers. Please try again."
            )
        }
    }

    /**
     * Step 4: User picks a server from the dialog. Auto-fill the display name
     * (if the user hasn't typed one yet) and the server URL.
     */
    fun onPlexServerSelected(server: PlexServerOption) {
        _uiState.value = _uiState.value.copy(
            name = if (_uiState.value.name.isBlank()) server.name else _uiState.value.name,
            baseUrl = server.url,
            token = server.accessToken,
            plexOAuthStep = PlexOAuthStep.IDLE,
            availablePlexServers = emptyList()
        )
    }

    /** Cancel an in-flight OAuth flow (e.g. user navigated back or switched to ABS). */
    fun cancelPlexOAuth() {
        pollingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            plexOAuthStep = PlexOAuthStep.IDLE,
            plexPinCode = null,
            plexPinId = null,
            availablePlexServers = emptyList()
        )
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    fun saveServer(existingId: String?) {
        val state = _uiState.value

        // Validation
        if (state.name.isBlank()) {
            _uiState.value = state.copy(saveError = "Display name is required.")
            return
        }
        if (state.baseUrl.isBlank() || state.token.isBlank()) {
            val msg = if (state.source == ServerSource.PLEX)
                "Please sign in with Plex and select a server first."
            else
                "Server URL and API token are required."
            _uiState.value = state.copy(saveError = msg)
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, saveError = null)
            val config = ServerConfig(
                id = existingId ?: Uuid.random().toString(),
                source = state.source,
                name = state.name.trim(),
                baseUrl = state.baseUrl.trimEnd('/'),
                token = state.token.trim(),
                libraryId = state.libraryId.takeIf { it.isNotBlank() }
            )
            try {
                serverRepository.save(config)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveError = "Couldn't save this server securely on your phone. Please try again."
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(isSaving = false, savedSuccessfully = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
