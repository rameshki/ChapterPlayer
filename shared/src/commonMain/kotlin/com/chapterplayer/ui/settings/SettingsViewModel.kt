@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.chapterplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.auth.normalizeServerAddress
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.domain.downloads.DownloadVolume
import com.chapterplayer.domain.downloads.INTERNAL_VOLUME_ID
import com.chapterplayer.domain.downloads.chooseVolume
import com.chapterplayer.domain.downloads.formatBytes
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

/** A place downloads can go, as the Settings list shows it: "SD card", "100 GB free". */
data class LocationOption(val id: String, val label: String, val detail: String)

/** The download locations available now and the one new downloads go to. */
data class DownloadLocationsUi(
    val options: List<LocationOption> = emptyList(),
    val selectedId: String = INTERNAL_VOLUME_ID
)

// ─────────────────────────────────────────────────────────────────────────────
// Server setup state
// ─────────────────────────────────────────────────────────────────────────────

/** Represents a Plex Media Server the user can connect to, discovered via plex.tv. */
data class PlexServerOption(
    val name: String,
    val url: String,
    val accessToken: String
) {
    /** Left out so the access token can never reach a log. */
    override fun toString() = "PlexServerOption(name=$name)"
}

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
    val plexOAuthError: String? = null,

    // ABS add flow (sign in, then choose libraries)
    val abs: AbsSetupState = AbsSetupState()
) {
    /** Left out so a token can never reach a log. */
    override fun toString() = "ServerSetupUiState(source=$source, name=$name, baseUrl=$baseUrl)"
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings ViewModel (server list + delete)
// ─────────────────────────────────────────────────────────────────────────────

class SettingsViewModel(
    private val serverRepository: ServerRepository,
    private val appSettings: AppSettings,
    private val downloads: DownloadController,
    private val storage: DownloadStorage
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

    val themeMode: StateFlow<ThemeMode> =
        appSettings.themeMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ThemeMode.SYSTEM
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            try {
                appSettings.setThemeMode(mode)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The choice is not saved; the screen keeps showing the saved one.
            }
        }
    }

    val downloadedOnly: StateFlow<Boolean> =
        appSettings.downloadedOnly.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    fun setDownloadedOnly(enabled: Boolean) {
        viewModelScope.launch {
            try {
                appSettings.setDownloadedOnly(enabled)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The choice is not saved; the screen keeps showing the saved one.
            }
        }
    }

    /** Android Auto starts the last book by itself when the phone connects to the car (on for new installs). */
    val resumeOnCarConnect: StateFlow<Boolean> =
        appSettings.resumeOnCarConnect.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    fun setResumeOnCarConnect(enabled: Boolean) {
        viewModelScope.launch {
            try {
                appSettings.setResumeOnCarConnect(enabled)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The choice is not saved; the screen keeps showing the saved one.
            }
        }
    }

    // Downloads

    /** Downloads wait for Wi-Fi (on for new installs). */
    val downloadWifiOnly: StateFlow<Boolean> =
        appSettings.downloadWifiOnly.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

    /** Saves the choice, then gives waiting and running downloads the new network rule. */
    fun setDownloadWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            try {
                appSettings.setDownloadWifiOnly(enabled)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@launch // Not saved, so nothing to apply.
            }
            try {
                downloads.reapplyWifiSetting()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The downloads keep their old rule until they are scheduled again (pause and resume, or app start).
            }
        }
    }

    /** At most this many bytes of downloads; null is no limit. */
    val downloadStorageLimit: StateFlow<Long?> =
        appSettings.downloadStorageLimitBytes.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    fun setDownloadStorageLimit(limitBytes: Long?) {
        viewModelScope.launch {
            try {
                appSettings.setDownloadStorageLimitBytes(limitBytes)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private val volumes = MutableStateFlow<List<DownloadVolume>>(emptyList())

    /** Where new downloads go; downloads already on the phone stay where they are. */
    val downloadLocations: StateFlow<DownloadLocationsUi> =
        combine(volumes, appSettings.downloadLocationId) { available, chosen ->
            DownloadLocationsUi(
                options = available.map { LocationOption(it.id, it.label, "${formatBytes(it.freeBytes)} free") },
                selectedId = chooseVolume(chosen, available)?.id ?: INTERNAL_VOLUME_ID
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadLocationsUi())

    init {
        refreshDownloadLocations()
    }

    /** Reads the volumes and their free space again (a card may have been put in or taken out). */
    fun refreshDownloadLocations() {
        viewModelScope.launch {
            try {
                volumes.value = storage.volumes()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun setDownloadLocation(id: String) {
        viewModelScope.launch {
            try {
                appSettings.setDownloadLocationId(id.takeIf { it != INTERNAL_VOLUME_ID })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
    private val plexClientIdentifier: PlexClientIdentifier,
    absSignIn: AbsSignInPort
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSetupUiState(plexClientId = plexClientIdentifier.value))
    val uiState: StateFlow<ServerSetupUiState> = _uiState

    private val absHandler = AbsSetupHandler(viewModelScope, absSignIn, serverRepository, _uiState)

    // ── Audiobookshelf add flow ──────────────────────────────────────────────

    fun onAbsAddressChanged(value: String) = absHandler.onAddressChanged(value)
    fun onAbsUsernameChanged(value: String) = absHandler.onUsernameChanged(value)
    fun onAbsPasswordChanged(value: String) = absHandler.onPasswordChanged(value)
    fun onAbsUseApiKeyChanged(value: Boolean) = absHandler.onUseApiKeyChanged(value)
    fun onAbsApiKeyChanged(value: String) = absHandler.onApiKeyChanged(value)
    fun onAbsLibraryToggled(id: String) = absHandler.onLibraryToggled(id)
    fun onAbsSignIn() = absHandler.signIn()
    fun onAbsSave() = absHandler.save()
    fun onAbsBack() = absHandler.onBack()
    fun onAbsSignInAgain(serverId: String) = absHandler.signInAgain(serverId)
    fun onAbsSecretsDiscarded() = absHandler.clearSecrets()

    private var pollingJob: Job? = null

    // ── Load existing server (edit mode) ─────────────────────────────────────

    fun loadServer(serverId: String) {
        viewModelScope.launch {
            val config = serverRepository.getById(serverId) ?: return@launch
            _uiState.value = ServerSetupUiState(
                name = config.name,
                source = config.source,
                baseUrl = config.baseUrl,
                // An Audiobookshelf edit never loads the stored token; saving keeps it from storage.
                token = if (config.source == ServerSource.ABS) "" else config.token,
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
        absHandler.clearSecrets()
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

        if (state.name.isBlank()) {
            _uiState.value = state.copy(saveError = "Display name is required.")
            return
        }
        if (existingId == null && (state.baseUrl.isBlank() || state.token.isBlank())) {
            val msg = if (state.source == ServerSource.PLEX)
                "Please sign in with Plex and select a server first."
            else
                "Server URL and API token are required."
            _uiState.value = state.copy(saveError = msg)
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, saveError = null)
            fun fail(message: String) {
                _uiState.value = _uiState.value.copy(isSaving = false, saveError = message)
            }
            try {
                // An edit starts from the stored server so its refresh token and
                // turned-off libraries are never erased by changing a name or address.
                val stored = existingId?.let { serverRepository.getById(it) }
                if (existingId != null && stored == null) {
                    fail(SERVER_GONE_MESSAGE)
                    return@launch
                }
                val config = when {
                    stored == null -> ServerConfig(
                        id = Uuid.random().toString(),
                        source = state.source,
                        name = state.name.trim(),
                        baseUrl = state.baseUrl.trimEnd('/'),
                        token = state.token.trim(),
                        libraryId = state.libraryId.takeIf { it.isNotBlank() }
                    )
                    // The stored server's own source decides, not the form's.
                    stored.source == ServerSource.ABS -> {
                        // The token is kept as stored, so only the address needs checking.
                        val address = normalizeServerAddress(state.baseUrl)
                        if (address == null) {
                            fail("Enter a valid server address.")
                            return@launch
                        }
                        // Servers saved by older builds may hold an address that was never normalised.
                        if (address != (normalizeServerAddress(stored.baseUrl) ?: stored.baseUrl)) {
                            // The tokens belong to the old host; only a new sign-in may move them.
                            fail("To change the address, sign in again below.")
                            return@launch
                        }
                        stored.copy(name = state.name.trim(), baseUrl = address)
                    }
                    else -> {
                        if (state.baseUrl.isBlank() || state.token.isBlank()) {
                            fail("Please sign in with Plex and select a server first.")
                            return@launch
                        }
                        stored.copy(
                            name = state.name.trim(),
                            baseUrl = state.baseUrl.trimEnd('/'),
                            token = state.token.trim(),
                            libraryId = state.libraryId.takeIf { it.isNotBlank() }
                        )
                    }
                }
                serverRepository.save(config)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(SAVE_FAILED_MESSAGE)
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
