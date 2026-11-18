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
import com.chapterplayer.domain.jellyfin.JELLYFIN_ID_MISSING_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_ID_UNREADABLE_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_INVALID_ADDRESS_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_NOT_JELLYFIN_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_REDIRECT_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_TLS_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_NO_BOOKS_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_OTHER_SERVER_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_TOO_OLD_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_WRONG_PASSWORD_TEXT
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_EXPIRED_TEXT
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_NOT_APPROVED_TEXT
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_OFF_TEXT
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_FINISH_TIMEOUT_MS
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_POLL_MS
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_READ_TIMEOUT_MS
import com.chapterplayer.domain.jellyfin.QUICK_CONNECT_START_TIMEOUT_MS
import com.chapterplayer.domain.jellyfin.QuickConnectPoll
import com.chapterplayer.domain.jellyfin.QuickConnectStep
import com.chapterplayer.domain.jellyfin.quickConnectNext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
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
    /** The Quick Connect wait that is running, or null (J3). */
    val quickConnect: QuickConnectUi? = null,
) {
    val canSave: Boolean get() = step == JellyfinStep.LIBRARIES && !isSaving && libraries.any { it.enabled }

    /** Left out so a password can never reach a log. */
    override fun toString() = "JellyfinSetupState(step=$step)"
}

/**
 * A Quick Connect wait on screen: the [code] to type on the other device (null while the request is being started) and
 * whether the last read of the request failed ([serverTrouble]). [toString] leaves the code out.
 */
data class QuickConnectUi(val code: String? = null, val serverTrouble: Boolean = false) {
    override fun toString() = "QuickConnectUi()"
}

/** The code ran out of time (five minutes) or the server forgot it; the words are [QUICK_CONNECT_EXPIRED_TEXT]. */
private class QuickConnectExpiredException : Exception("The code expired")

private const val JELLYFIN_UNREACHABLE_TEXT = "Couldn't reach the server. Check the address and your network."
private const val JELLYFIN_SIGN_IN_FAILED_TEXT = "Couldn't sign in. Please try again."

/** The Jellyfin server id kept for a row at its first sign-in, as far as it can be read. */
internal sealed interface StoredServerId {
    data class Known(val id: String) : StoredServerId
    data object Missing : StoredServerId
    data object Unreadable : StoredServerId
}

/** Reads the id kept for [rowId]; a read that throws is [StoredServerId.Unreadable], none kept is [StoredServerId.Missing]. */
internal suspend fun storedServerId(statusStore: ServerStatusStore, rowId: String): StoredServerId =
    try {
        statusStore.get(rowId).jellyfinServerId?.takeIf { it.isNotBlank() }?.let { StoredServerId.Known(it) } ?: StoredServerId.Missing
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        StoredServerId.Unreadable
    }

/** The words for a failed Jellyfin sign-in (spec section 9). */
internal fun jellyfinSignInMessage(error: JellyfinSignInError): String = when (error) {
    JellyfinSignInError.Unreachable -> JELLYFIN_UNREACHABLE_TEXT
    JellyfinSignInError.NotJellyfin -> JELLYFIN_NOT_JELLYFIN_TEXT
    is JellyfinSignInError.TooOld -> JELLYFIN_TOO_OLD_TEXT
    JellyfinSignInError.WrongCredentials -> JELLYFIN_WRONG_PASSWORD_TEXT
    JellyfinSignInError.NoBooksLibrary -> JELLYFIN_NO_BOOKS_TEXT
    JellyfinSignInError.OtherServer -> JELLYFIN_OTHER_SERVER_TEXT
    is JellyfinSignInError.ServerError -> "The server returned an error (code ${error.code})."
    JellyfinSignInError.InvalidAddress -> JELLYFIN_INVALID_ADDRESS_TEXT
    JellyfinSignInError.Redirected -> JELLYFIN_REDIRECT_TEXT
    JellyfinSignInError.SecureConnection -> JELLYFIN_TLS_TEXT
    JellyfinSignInError.QuickConnectOff -> QUICK_CONNECT_OFF_TEXT
    JellyfinSignInError.QuickConnectNotApproved -> QUICK_CONNECT_NOT_APPROVED_TEXT
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

    /**
     * The address [result] was signed in to. Save stores the token under THIS address, never under whatever the field holds by
     * then: the field can be edited while a Quick Connect wait runs, and a token for one server must not be saved for another.
     */
    private var resultAddress: String? = null

    /** The running sign-in (password or Quick Connect wait). */
    private var signInJob: Job? = null

    /** The running Quick Connect wait, so Cancel, Back and leaving the screen can end it. */
    private var quickJob: Job? = null

    /** Counts Quick Connect runs, so a run that was cancelled cannot clear the state of the next one. */
    private var quickRun = 0

    private fun edit(change: JellyfinSetupState.() -> JellyfinSetupState) {
        state.update { it.copy(jellyfin = it.jellyfin.change()) }
    }

    /** Called when the add flow starts (the Jellyfin chip is chosen): the new row's id, made once. */
    fun startAdd() = edit { if (pendingRowId.isBlank()) copy(pendingRowId = Uuid.random().toString()) else this }

    fun onAddressChanged(value: String) = edit { copy(address = value) }
    fun onUsernameChanged(value: String) = edit { copy(username = value) }
    fun onPasswordChanged(value: String) = edit { copy(password = value) }

    /**
     * Drops the typed password so it does not wait in memory after the user leaves the Jellyfin form, and ends a Quick Connect
     * wait: leaving the screen stops the polling and forgets the secret.
     */
    fun clearSecrets() {
        cancelQuickConnect()
        edit { copy(password = "") }
    }

    /** Cancel, Back or leaving the screen: the polling stops and the secret is forgotten. There is nothing to tell the server. */
    fun cancelQuickConnect() {
        val job = quickJob
        val running = job?.isActive == true
        // Also between the start of the wait and its first code, and while the approved sign-in is being finished.
        if (!running && state.value.jellyfin.quickConnect == null) return
        quickRun++
        job?.cancel()
        quickJob = null
        edit { copy(quickConnect = null, isSigningIn = false, signInError = null) }
    }

    fun onLibraryToggled(id: String) = edit {
        copy(libraries = libraries.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun onBack() {
        result = null
        resultAddress = null
        edit { copy(step = JellyfinStep.SIGN_IN, password = "", libraries = emptyList(), saveError = null) }
    }

    fun signIn() = signInWith { current ->
        signInPort().withPassword(current.address.trim(), current.pendingRowId, current.username.trim(), current.password)
    }

    /** Add flow with Quick Connect: no password; the wait shows a code and ends in the same libraries step. */
    fun signInWithQuickConnect() {
        if (state.value.jellyfin.quickConnect == null && normalizeServerAddress(state.value.jellyfin.address) == null) {
            edit { copy(signInError = JELLYFIN_INVALID_ADDRESS_TEXT) }
            return
        }
        val idle = !state.value.jellyfin.isSigningIn
        signInWith { current -> quickConnectSignIn(current.address.trim(), current.pendingRowId, expectedServerId = null) }
        if (idle) quickJob = signInJob
    }

    private fun signInWith(authenticate: suspend (JellyfinSetupState) -> JellyfinSignInResult) {
        startAdd()
        val current = state.value.jellyfin
        if (current.isSigningIn) return
        edit { copy(isSigningIn = true, signInError = null) }
        signInJob = scope.launch {
            try {
                val signedIn = authenticate(current)
                result = signedIn
                resultAddress = current.address
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
        val address = normalizeServerAddress(resultAddress ?: return) ?: return
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
            // The server's id is kept first: without it a later Sign in again or address change could not check that
            // the address still belongs to this server, so a failure here fails the whole setup.
            try {
                statusStore.update(config.id) { it.copy(jellyfinServerId = signedIn.serverId) }
                serverRepository.save(config)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                edit { copy(isSaving = false, saveError = SAVE_FAILED_MESSAGE) }
                return@launch
            }
            result = null
            resultAddress = null
            edit { copy(isSaving = false) }
            state.update { it.copy(savedSuccessfully = true) }
        }
    }

    /**
     * Edit mode: signs in again at the address in the form for the stored row (same row id, so the same DeviceId),
     * refusing a server whose `Id` differs from the one kept at the first sign-in. On success only the token (and the
     * address, and the name if edited) change; a failure saves nothing.
     */
    fun signInAgain(serverId: String) = signInAgainWith(serverId) { address, expected ->
        val current = state.value.jellyfin
        signInPort().withPassword(address, serverId, current.username.trim(), current.password, expectedServerId = expected)
    }

    /** Edit mode with Quick Connect: the same checks as [signInAgain] (same server, same row id and DeviceId), without the password. */
    fun signInAgainWithQuickConnect(serverId: String) {
        val idle = !state.value.jellyfin.isSigningIn
        signInAgainWith(serverId) { address, expected -> quickConnectSignIn(address, serverId, expected) }
        if (idle) quickJob = signInJob
    }

    private fun signInAgainWith(serverId: String, authenticate: suspend (address: String, expectedServerId: String) -> JellyfinSignInResult) {
        val current = state.value.jellyfin
        if (current.isSigningIn) return
        val address = normalizeServerAddress(state.value.baseUrl)
        if (address == null) {
            edit { copy(signInError = "Enter a valid server address.") }
            return
        }
        edit { copy(isSigningIn = true, signInError = null) }
        state.update { it.copy(saveError = null) }
        signInJob = scope.launch {
            val stored = storedOrNull(serverId)
            if (stored == null) {
                edit { copy(isSigningIn = false) }
                state.update { it.copy(saveError = SERVER_GONE_MESSAGE) }
                return@launch
            }
            // Fail closed: without the id kept at the first sign-in, the answering server cannot be checked.
            val expected = when (val id = storedServerId(statusStore, serverId)) {
                StoredServerId.Unreadable -> {
                    edit { copy(isSigningIn = false, signInError = JELLYFIN_ID_UNREADABLE_TEXT) }
                    return@launch
                }
                StoredServerId.Missing -> {
                    edit { copy(isSigningIn = false, signInError = JELLYFIN_ID_MISSING_TEXT) }
                    return@launch
                }
                is StoredServerId.Known -> id.id
            }
            val signedIn = try {
                authenticate(address, expected)
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

    /**
     * Quick Connect, start to end (spec 5.1): starts a request (the same checks as a password sign-in), shows its code, then
     * asks every [QUICK_CONNECT_POLL_MS] whether it was approved, for at most five minutes ([quickConnectNext]; the ticks are
     * counted, not timed, so a slow read does not shorten the wait). An approval signs in. The secret lives in this function
     * and the port's request only; the screen gets the code.
     */
    private suspend fun quickConnectSignIn(address: String, rowId: String, expectedServerId: String?): JellyfinSignInResult {
        val port = signInPort()
        val run = ++quickRun
        edit { copy(quickConnect = QuickConnectUi()) }
        try {
            // Every call has its own limit (a host that goes dark must not stretch the wait): a hung start is a network failure.
            val request = withTimeoutOrNull(QUICK_CONNECT_START_TIMEOUT_MS) { port.startQuickConnect(address, rowId, expectedServerId) }
                ?: throw JellyfinSignInException(JellyfinSignInError.Unreachable)
            edit { copy(quickConnect = QuickConnectUi(code = request.code)) }
            var elapsedMs = 0L
            while (true) {
                delay(QUICK_CONNECT_POLL_MS)
                elapsedMs += QUICK_CONNECT_POLL_MS
                // A read that hangs is a failed read, and its limit counts as time spent waiting.
                val poll = withTimeoutOrNull(QUICK_CONNECT_READ_TIMEOUT_MS) { port.pollQuickConnect(address, rowId, request) }
                    ?: QuickConnectPoll.UNREACHABLE.also { elapsedMs += QUICK_CONNECT_READ_TIMEOUT_MS }
                when (val step = quickConnectNext(elapsedMs, poll)) {
                    is QuickConnectStep.Wait -> edit { copy(quickConnect = quickConnect?.copy(serverTrouble = step.serverTrouble)) }
                    QuickConnectStep.Expired -> throw QuickConnectExpiredException()
                    QuickConnectStep.SignIn ->
                        return withTimeoutOrNull(QUICK_CONNECT_FINISH_TIMEOUT_MS) { port.finishQuickConnect(address, rowId, request, expectedServerId) }
                            ?: throw JellyfinSignInException(JellyfinSignInError.Unreachable)
                }
            }
        } finally {
            // Only this run's own state: a cancelled run must not wipe the wait that was started after it.
            if (quickRun == run) edit { copy(quickConnect = null) }
        }
    }

    private fun signInPort(): JellyfinSignInPort = port ?: throw JellyfinSignInException(JellyfinSignInError.Unreachable)

    private fun messageOf(e: Exception): String = when (e) {
        is JellyfinSignInException -> jellyfinSignInMessage(e.error)
        is QuickConnectExpiredException -> QUICK_CONNECT_EXPIRED_TEXT
        else -> JELLYFIN_SIGN_IN_FAILED_TEXT
    }
}
