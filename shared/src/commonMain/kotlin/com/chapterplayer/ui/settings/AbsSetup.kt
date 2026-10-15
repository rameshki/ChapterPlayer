@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.chapterplayer.ui.settings

import com.chapterplayer.data.auth.AbsSignInError
import com.chapterplayer.data.auth.AbsSignInException
import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.auth.AbsSignInResult
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** The two steps of adding an Audiobookshelf server. */
enum class AbsStep { SIGN_IN, LIBRARIES }

/** One book library in the "Choose libraries" step. */
data class LibraryChoice(val id: String, val name: String, val enabled: Boolean)

/** State of the Audiobookshelf add flow. The password is cleared once sign-in succeeds. */
data class AbsSetupState(
    val step: AbsStep = AbsStep.SIGN_IN,
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val useApiKey: Boolean = false,
    val apiKey: String = "",
    val signInError: String? = null,
    val isSigningIn: Boolean = false,
    val libraries: List<LibraryChoice> = emptyList(),
    val isSaving: Boolean = false,
    val saveError: String? = null,
) {
    val canSave: Boolean get() = step == AbsStep.LIBRARIES && !isSaving && libraries.any { it.enabled }

    /** Left out so a password or key can never reach a log. */
    override fun toString() = "AbsSetupState(step=$step)"
}

internal const val SERVER_GONE_MESSAGE = "That server no longer exists."
internal const val SAVE_FAILED_MESSAGE = "Couldn't save this server securely on your phone. Please try again."
private const val SIGN_IN_FAILED_MESSAGE = "Couldn't sign in. Please try again."

internal fun absSignInMessage(error: AbsSignInError): String = when (error) {
    AbsSignInError.Unreachable -> "Couldn't reach the server. Check the address and your network."
    AbsSignInError.NotAudiobookshelf -> "That address doesn't look like an Audiobookshelf server."
    AbsSignInError.LocalLoginOff -> "This server only allows OpenID sign-in, which isn't supported yet."
    AbsSignInError.WrongCredentials -> "The username or password is wrong."
    AbsSignInError.TooManyAttempts -> "Too many attempts. Wait a few minutes and try again."
    AbsSignInError.Rejected -> "The server didn't accept that API token."
    AbsSignInError.NoBookLibraries -> "This server has no audiobook libraries."
    is AbsSignInError.ServerError -> "The server returned an error (code ${error.code})."
}

/** The host of a server address, used as the display name when the user leaves it empty. */
internal fun hostOf(baseUrl: String): String {
    val authority = baseUrl.substringAfter("://").substringBefore('/')
    return if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
}

/** Sign-in and library choice for a new Audiobookshelf server. Works on the shared setup state. */
internal class AbsSetupHandler(
    private val scope: CoroutineScope,
    private val port: AbsSignInPort,
    private val serverRepository: ServerRepository,
    private val state: MutableStateFlow<ServerSetupUiState>,
) {
    private var result: AbsSignInResult? = null

    private fun edit(change: AbsSetupState.() -> AbsSetupState) {
        state.update { it.copy(abs = it.abs.change()) }
    }

    fun onAddressChanged(value: String) = edit { copy(address = value) }
    fun onUsernameChanged(value: String) = edit { copy(username = value) }
    fun onPasswordChanged(value: String) = edit { copy(password = value) }
    fun onUseApiKeyChanged(value: Boolean) = edit {
        copy(useApiKey = value, signInError = null, password = if (value) "" else password)
    }

    /** Drops what was typed so a password or key does not wait in memory after the user leaves the ABS form. */
    fun clearSecrets() = edit { copy(password = "", apiKey = "") }
    fun onApiKeyChanged(value: String) = edit { copy(apiKey = value) }

    fun onLibraryToggled(id: String) = edit {
        copy(libraries = libraries.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun onBack() {
        result = null
        edit { copy(step = AbsStep.SIGN_IN, password = "", libraries = emptyList(), saveError = null) }
    }

    fun signIn() {
        val current = state.value.abs
        if (current.isSigningIn) return
        edit { copy(isSigningIn = true, signInError = null) }
        scope.launch {
            try {
                val signedIn = if (current.useApiKey) {
                    port.withApiKey(current.address.trim(), current.apiKey.trim())
                } else {
                    port.withPassword(current.address.trim(), current.username.trim(), current.password)
                }
                result = signedIn
                edit {
                    copy(
                        step = AbsStep.LIBRARIES,
                        isSigningIn = false,
                        password = "",
                        apiKey = "",
                        libraries = signedIn.libraries.map { LibraryChoice(it.id, it.name, true) },
                        saveError = null,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = if (e is AbsSignInException) absSignInMessage(e.error) else SIGN_IN_FAILED_MESSAGE
                edit { copy(isSigningIn = false, signInError = message) }
            }
        }
    }

    fun save() {
        val signedIn = result ?: return
        val current = state.value.abs
        if (!current.canSave) return
        edit { copy(isSaving = true, saveError = null) }
        val typedName = state.value.name.trim()
        val config = ServerConfig(
            id = Uuid.random().toString(),
            source = ServerSource.ABS,
            name = typedName.ifEmpty { hostOf(signedIn.baseUrl) },
            baseUrl = signedIn.baseUrl,
            token = signedIn.token,
            refreshToken = signedIn.refreshToken,
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
            result = null
            edit { copy(isSaving = false) }
            state.update { it.copy(savedSuccessfully = true) }
        }
    }
}
