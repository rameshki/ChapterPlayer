package com.chapterplayer.data.remote.jellyfin

import com.chapterplayer.data.auth.JellyfinSignInError
import com.chapterplayer.data.auth.JellyfinSignInException
import com.chapterplayer.data.auth.JellyfinSignInPort
import com.chapterplayer.data.auth.JellyfinSignInResult
import com.chapterplayer.data.auth.normalizeServerAddress
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.domain.jellyfin.JellyfinServerCheck
import com.chapterplayer.domain.jellyfin.checkJellyfinServer
import com.chapterplayer.domain.jellyfin.jellyfinBookViews
import com.chapterplayer.domain.jellyfin.jellyfinDeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Signs in to a Jellyfin server: public info on the probe client ([probeApi]: no redirects, no token) -> version and
 * product check -> the identity check (Sign in again) -> `AuthenticateByName` -> the user's views with the new token
 * -> the books views. The password is sent only once the address answered as a Jellyfin server new enough (and, on
 * Sign in again, as the same server). Stores nothing. Throws only [JellyfinSignInException]; messages of caught
 * exceptions are never used (they can hold response text).
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
        val base = normalizeServerAddress(address) ?: fail(JellyfinSignInError.NotJellyfin)
        // A web page, a proxy's login redirect or another app answering the address is "not Jellyfin".
        val info = call({ if (it >= 500) JellyfinSignInError.ServerError(it) else JellyfinSignInError.NotJellyfin }) {
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
        val deviceId = jellyfinDeviceId(installId.value, serverRowId)
        val auth = call({ if (it == 401) JellyfinSignInError.WrongCredentials else JellyfinSignInError.ServerError(it) }) {
            api.authenticateByName(base, deviceId, username, password)
        }
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
                    is IOException -> JellyfinSignInError.Unreachable
                    // A body that is not Jellyfin's JSON (a web page, another app).
                    else -> JellyfinSignInError.NotJellyfin
                }
            )
        }

    private fun fail(error: JellyfinSignInError): Nothing = throw JellyfinSignInException(error)
}
