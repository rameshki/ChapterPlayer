@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.chapterplayer.ui.settings

import com.chapterplayer.data.auth.JellyfinSignInError
import com.chapterplayer.data.auth.JellyfinSignInException
import com.chapterplayer.data.auth.JellyfinSignInPort
import com.chapterplayer.data.auth.JellyfinSignInResult
import com.chapterplayer.data.auth.normalizeServerAddress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.sync.ServerStatusStore
import com.chapterplayer.domain.jellyfin.JELLYFIN_NOT_JELLYFIN_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_NO_BOOKS_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_OTHER_SERVER_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_TOO_OLD_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_WRONG_PASSWORD_TEXT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** The two steps of adding a Jellyfin server. */
enum class JellyfinStep { SIGN_IN, LIBRARIES }

/**
 * State of the Jellyfin add flow and of its Sign in again. The password is cleared once a sign-in succeeds.
 * [pendingRowId] is the id the new server row will have: made when the add flow starts, because the DeviceId sent at
 * sign-in is built from it and must not change between sign-in and use.
 */
data class JellyfinSetupState(
    val step: JellyfinStep = JellyfinStep.SIGN_IN,
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val signInError: String? = null,
    val isSigningIn: Boolean = false,
    val libraries: List<LibraryChoice> = emptyList(),
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val pendingRowId: String = "",
) {
    val canSave: Boolean get() = step == JellyfinStep.LIBRARIES && !isSaving && libraries.any { it.enabled }

    /** Left out so a password can never reach a log. */
    override fun toString() = "JellyfinSetupState(step=$step)"
}

private const val JELLYFIN_UNREACHABLE_TEXT = "Couldn't reach the server. Check the address and your network."
private const val JELLYFIN_SIGN_IN_FAILED_TEXT = "Couldn't sign in. Please try again."

/** The words for a failed Jellyfin sign-in (spec section 9). */
internal fun jellyfinSignInMessage(error: JellyfinSignInError): String = when (error) {
    JellyfinSignInError.Unreachable -> JELLYFIN_UNREACHABLE_TEXT
    JellyfinSignInError.NotJellyfin -> JELLYFIN_NOT_JELLYFIN_TEXT
    is JellyfinSignInError.TooOld -> JELLYFIN_TOO_OLD_TEXT
    JellyfinSignInError.WrongCredentials -> JELLYFIN_WRONG_PASSWORD_TEXT
    JellyfinSignInError.NoBooksLibrary -> JELLYFIN_NO_BOOKS_TEXT
    JellyfinSignInError.OtherServer -> JELLYFIN_OTHER_SERVER_TEXT
    is JellyfinSignInError.ServerError -> "The server returned an error (code ${error.code})."
}

/**
 * Sign-in, the books views check-list and Sign in again for a Jellyfin server. Works on the shared setup state. The
 * token goes to [serverRepository] (which keeps it in the credential store, never in Room); the server's own `Id` goes
 * to [statusStore], so Sign in again can refuse an address that now belongs to another server.
 */
internal class JellyfinSetupHandler(
    private val scope: CoroutineScope,
    private val port: JellyfinSignInPort?,
    private val serverRepository: ServerRepository,
    private val statusStore: ServerStatusStore,
    private val state: MutableStateFlow<ServerSetupUiState>,
) {
    private var result: JellyfinSignInResult? = null

    private fun edit(change: JellyfinSetupState.() -> JellyfinSetupState) {
        state.update { it.copy(jellyfin = it.jellyfin.change()) }
    }

    /** Called when the add flow starts (the Jellyfin chip is chosen): the new row's id, made once. */
    fun startAdd() = edit { if (pendingRowId.isBlank()) copy(pendingRowId = Uuid.random().toString()) else this }

    fun onAddressChanged(value: String) = edit { copy(address = value) }
    fun onUsernameChanged(value: String) = edit { copy(username = value) }
    fun onPasswordChanged(value: String) = edit { copy(password = value) }

    /** Drops the typed password so it does not wait in memory after the user leaves the Jellyfin form. */
    fun clearSecrets() = edit { copy(password = "") }

    fun onLibraryToggled(id: String) = edit {
        copy(libraries = libraries.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun onBack() {
        result = null
        edit { copy(step = JellyfinStep.SIGN_IN, password = "", libraries = emptyList(), saveError = null) }
    }

    fun signIn() {
        startAdd()
        val current = state.value.jellyfin
        if (current.isSigningIn) return
        edit { copy(isSigningIn = true, signInError = null) }
        scope.launch {
            try {
                val signedIn = signInPort().withPassword(current.address.trim(), current.pendingRowId, current.username.trim(), current.password)
                result = signedIn
                edit {
                    copy(
                        step = JellyfinStep.LIBRARIES,
                        isSigningIn = false,
                        password = "",
                        libraries = signedIn.libraries.map { LibraryChoice(it.id, it.name, true) },
                        saveError = null,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                edit { copy(isSigningIn = false, signInError = messageOf(e)) }
            }
        }
    }

    fun save() {
        val signedIn = result ?: return
        val current = state.value.jellyfin
        if (!current.canSave) return
        val address = normalizeServerAddress(current.address) ?: return
        edit { copy(isSaving = true, saveError = null) }
        val typedName = state.value.name.trim()
        val config = ServerConfig(
            id = current.pendingRowId,
            source = ServerSource.JELLYFIN,
            name = typedName.ifEmpty { hostOf(address) },
            baseUrl = address,
            token = signedIn.token,
            excludedLibraryIds = current.libraries.filter { !it.enabled }.map { it.id }.toSet(),
            libraryId = null,
        )
        scope.launch {
            try {
                serverRepository.save(config)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                edit { copy(isSaving = false, saveError = SAVE_FAILED_MESSAGE) }
                return@launch
            }
            rememberServerId(config.id, signedIn.serverId)
            result = null
            edit { copy(isSaving = false) }
            state.update { it.copy(savedSuccessfully = true) }
        }
    }

    /**
     * Edit mode: signs in again at the address in the form for the stored row (same row id, so the same DeviceId),
     * refusing a server whose `Id` differs from the one kept at the first sign-in. On success only the token (and the
     * address, and the name if edited) change; a failure saves nothing.
     */
    fun signInAgain(serverId: String) {
        val current = state.value.jellyfin
        if (current.isSigningIn) return
        val address = normalizeServerAddress(state.value.baseUrl)
        if (address == null) {
            edit { copy(signInError = "Enter a valid server address.") }
            return
        }
        edit { copy(isSigningIn = true, signInError = null) }
        state.update { it.copy(saveError = null) }
        scope.launch {
            val stored = storedOrNull(serverId)
            if (stored == null) {
                edit { copy(isSigningIn = false) }
                state.update { it.copy(saveError = SERVER_GONE_MESSAGE) }
                return@launch
            }
            val expected = try {
                statusStore.get(serverId).jellyfinServerId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            val signedIn = try {
                signInPort().withPassword(address, serverId, current.username.trim(), current.password, expectedServerId = expected)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                edit { copy(isSigningIn = false, signInError = messageOf(e)) }
                return@launch
            }
            val typedName = state.value.name.trim()
            try {
                serverRepository.save(stored.copy(name = typedName.ifEmpty { stored.name }, baseUrl = address, token = signedIn.token))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                edit { copy(isSigningIn = false) }
                state.update { it.copy(saveError = SAVE_FAILED_MESSAGE) }
                return@launch
            }
            rememberServerId(serverId, signedIn.serverId)
            edit { copy(isSigningIn = false, password = "") }
            state.update { it.copy(savedSuccessfully = true) }
        }
    }

    private suspend fun storedOrNull(serverId: String): ServerConfig? =
        try {
            serverRepository.getById(serverId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

    /** Keeps the server's `Id` for the identity check of a later Sign in again; best effort (the row is saved). */
    private suspend fun rememberServerId(rowId: String, jellyfinServerId: String) {
        try {
            statusStore.update(rowId) { it.copy(jellyfinServerId = jellyfinServerId) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    private fun signInPort(): JellyfinSignInPort = port ?: throw JellyfinSignInException(JellyfinSignInError.Unreachable)

    private fun messageOf(e: Exception): String =
        if (e is JellyfinSignInException) jellyfinSignInMessage(e.error) else JELLYFIN_SIGN_IN_FAILED_TEXT
}
