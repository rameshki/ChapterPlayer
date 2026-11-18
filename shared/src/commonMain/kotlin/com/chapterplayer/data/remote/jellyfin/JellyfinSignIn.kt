package com.chapterplayer.data.remote.jellyfin

import com.chapterplayer.data.auth.JellyfinSignInError
import com.chapterplayer.data.auth.JellyfinSignInException
import com.chapterplayer.data.auth.JellyfinSignInPort
import com.chapterplayer.data.auth.JellyfinSignInResult
import com.chapterplayer.data.auth.QuickConnectRequest
import com.chapterplayer.data.auth.normalizeServerAddress
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinAuthResultDto
import com.chapterplayer.domain.jellyfin.JellyfinServerCheck
import com.chapterplayer.domain.jellyfin.QuickConnectPoll
import com.chapterplayer.domain.jellyfin.checkJellyfinServer
import com.chapterplayer.domain.jellyfin.jellyfinBookViews
import com.chapterplayer.domain.jellyfin.jellyfinDeviceId
import com.chapterplayer.domain.plex.ProblemKind
import com.chapterplayer.domain.plex.classifyConnectionError
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Signs in to a Jellyfin server: public info on the probe client ([probeApi]: no redirects, no token) -> version and
 * product check -> the identity check (Sign in again) -> `AuthenticateByName` -> the user's views with the new token
 * -> the books views. The password is sent only once the address answered as a Jellyfin server new enough (and, on
 * Sign in again, as the same server). Stores nothing. Throws only [JellyfinSignInException]; messages of caught
 * exceptions are never used (they can hold response text).
 *
 * Quick Connect (J3, spec 5.1) is the same sign-in without the password: [startQuickConnect] runs the same checks and asks
 * the server for a code, [pollQuickConnect] asks whether it was approved, and [finishQuickConnect] signs in with the secret
 * and goes through the same finish as the password (identity check, views, books libraries). The secret is a credential:
 * it is in memory only, never in a message or a log.
 */
class JellyfinSignIn(
    private val api: JellyfinApiService,
    private val probeApi: JellyfinApiService,
    private val installId: InstallId,
) : JellyfinSignInPort {

    override suspend fun withPassword(
        address: String,
        serverRowId: String,
        username: String,
        password: String,
        expectedServerId: String?,
    ): JellyfinSignInResult {
        val base = normalizeServerAddress(address) ?: fail(JellyfinSignInError.InvalidAddress)
        val check = probe(base, expectedServerId)
        val deviceId = jellyfinDeviceId(installId.value, serverRowId)
        val auth = call({ if (it == 401) JellyfinSignInError.WrongCredentials else JellyfinSignInError.ServerError(it) }) {
            api.authenticateByName(base, deviceId, username, password)
        }
        return finish(base, deviceId, serverRowId, check, auth)
    }

    override suspend fun startQuickConnect(address: String, serverRowId: String, expectedServerId: String?): QuickConnectRequest {
        val base = normalizeServerAddress(address) ?: fail(JellyfinSignInError.InvalidAddress)
        probe(base, expectedServerId)
        val deviceId = jellyfinDeviceId(installId.value, serverRowId)
        // Off, or a server that refuses to say: the person is told to use the password. A network failure is a network failure.
        val enabled = call({ JellyfinSignInError.QuickConnectOff }) { api.quickConnectEnabled(base, deviceId) }
        if (!enabled) fail(JellyfinSignInError.QuickConnectOff)
        // Turned off since the check: Initiate answers 401.
        val started = call({ if (it == 401 || it == 403 || it == 404) JellyfinSignInError.QuickConnectOff else JellyfinSignInError.ServerError(it) }) {
            api.quickConnectInitiate(base, deviceId)
        }
        val secret = started.secret?.takeIf { it.isNotBlank() } ?: fail(JellyfinSignInError.ServerError(0))
        val code = started.code?.takeIf { it.isNotBlank() } ?: fail(JellyfinSignInError.ServerError(0))
        return QuickConnectRequest(secret, code)
    }

    override suspend fun pollQuickConnect(address: String, serverRowId: String, request: QuickConnectRequest): QuickConnectPoll {
        val base = normalizeServerAddress(address) ?: return QuickConnectPoll.UNREACHABLE
        return try {
            val answer = api.quickConnectConnect(base, jellyfinDeviceId(installId.value, serverRowId), request.secret)
            if (answer.authenticated == true) QuickConnectPoll.APPROVED else QuickConnectPoll.WAITING
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            // The server does not know the secret (expired, or never there); anything else (a 5xx) is tried again.
            if (e.status.value == 401 || e.status.value == 404) QuickConnectPoll.UNKNOWN else QuickConnectPoll.UNREACHABLE
        } catch (_: Throwable) {
            QuickConnectPoll.UNREACHABLE
        }
    }

    override suspend fun finishQuickConnect(
        address: String,
        serverRowId: String,
        request: QuickConnectRequest,
        expectedServerId: String?,
    ): JellyfinSignInResult {
        val base = normalizeServerAddress(address) ?: fail(JellyfinSignInError.InvalidAddress)
        val check = probe(base, expectedServerId)
        val deviceId = jellyfinDeviceId(installId.value, serverRowId)
        val auth = call({
            if (it == 400 || it == 401 || it == 403 || it == 404) JellyfinSignInError.QuickConnectNotApproved else JellyfinSignInError.ServerError(it)
        }) {
            api.authenticateWithQuickConnect(base, deviceId, request.secret)
        }
        return finish(base, deviceId, serverRowId, check, auth)
    }

    /**
     * What the address answers, before any credential is sent: a web page or another app is "not Jellyfin"; a redirect (http
     * to https, a path, a proxy's login page) is said to be one, and is not followed. Then the version and product check, and
     * with [expectedServerId] (Sign in again) the identity check.
     */
    private suspend fun probe(base: String, expectedServerId: String?): JellyfinServerCheck.Ok {
        val info = call({
            when {
                it in 300..399 -> JellyfinSignInError.Redirected
                it >= 500 -> JellyfinSignInError.ServerError(it)
                else -> JellyfinSignInError.NotJellyfin
            }
        }) {
            probeApi.getPublicInfo(base)
        }
        val check = when (val c = checkJellyfinServer(info)) {
            JellyfinServerCheck.NotJellyfin -> fail(JellyfinSignInError.NotJellyfin)
            is JellyfinServerCheck.TooOld -> fail(JellyfinSignInError.TooOld(c.version))
            is JellyfinServerCheck.Ok -> c
        }
        if (expectedServerId != null && !expectedServerId.equals(check.serverId, ignoreCase = true)) {
            fail(JellyfinSignInError.OtherServer)
        }
        return check
    }

    /** The same finish for a password and for Quick Connect: the token, the user's views with it, the books libraries. */
    private suspend fun finish(
        base: String,
        deviceId: String,
        serverRowId: String,
        check: JellyfinServerCheck.Ok,
        auth: JellyfinAuthResultDto,
    ): JellyfinSignInResult {
        val token = auth.accessToken?.takeIf { it.isNotBlank() } ?: fail(JellyfinSignInError.ServerError(0))
        // A freshly issued token being refused is a server problem, not the user's.
        val views = call({ JellyfinSignInError.ServerError(it) }) { api.getUserViews(base, deviceId, token) }
        val libraries = jellyfinBookViews(views.items, serverRowId).ifEmpty { fail(JellyfinSignInError.NoBooksLibrary) }
        return JellyfinSignInResult(token, check.serverId, auth.user?.id.orEmpty(), check.version, libraries)
    }

    private suspend fun <T> call(whenHttp: (Int) -> JellyfinSignInError, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw JellyfinSignInException(
                when (t) {
                    // Before IOException: HttpStatusException is one.
                    is HttpStatusException -> whenHttp(t.status.value)
                    is IOException ->
                        if (classifyConnectionError(t).kind == ProblemKind.TLS) JellyfinSignInError.SecureConnection
                        else JellyfinSignInError.Unreachable
                    // A body that is not Jellyfin's JSON (a web page, another app).
                    else -> JellyfinSignInError.NotJellyfin
                }
            )
        }

    private fun fail(error: JellyfinSignInError): Nothing = throw JellyfinSignInException(error)
}
