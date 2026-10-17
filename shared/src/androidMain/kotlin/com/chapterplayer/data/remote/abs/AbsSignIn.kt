package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.auth.AbsLibraryInfo
import com.chapterplayer.data.auth.AbsSignInError
import com.chapterplayer.data.auth.AbsSignInException
import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.auth.AbsSignInResult
import com.chapterplayer.data.auth.normalizeServerAddress
import com.chapterplayer.data.remote.HttpStatusException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Signs in to an Audiobookshelf server and lists its book libraries. Throws only
 * [AbsSignInException]. Messages of caught exceptions are never used: they can hold response text.
 */
class AbsSignIn(private val api: AbsApiService) : AbsSignInPort {

    override suspend fun withPassword(address: String, username: String, password: String): AbsSignInResult {
        val baseUrl = normalizeServerAddress(address) ?: fail(AbsSignInError.NotAudiobookshelf)
        checkStatus(baseUrl)
        val loginHttp: (Int) -> AbsSignInError = {
            when (it) {
                401, 403 -> AbsSignInError.WrongCredentials
                429 -> AbsSignInError.TooManyAttempts
                else -> AbsSignInError.ServerError(it)
            }
        }
        val user = call(loginHttp) { api.login(baseUrl, username, password) }.user
        val token = user.accessToken?.takeIf { it.isNotBlank() } ?: user.token?.takeIf { it.isNotBlank() }
            ?: fail(AbsSignInError.ServerError(0))
        // A freshly issued token being refused is a server problem, not the user's.
        val libraries = bookLibraries(baseUrl, token) { AbsSignInError.ServerError(it) }
        return AbsSignInResult(baseUrl, token, user.refreshToken, libraries)
    }

    override suspend fun withApiKey(address: String, apiKey: String): AbsSignInResult {
        val baseUrl = normalizeServerAddress(address) ?: fail(AbsSignInError.NotAudiobookshelf)
        checkStatus(baseUrl)
        val libraries = bookLibraries(baseUrl, apiKey) {
            if (it == 401 || it == 403) AbsSignInError.Rejected else AbsSignInError.ServerError(it)
        }
        return AbsSignInResult(baseUrl, apiKey, null, libraries)
    }

    private suspend fun checkStatus(baseUrl: String) {
        val status = call({ code ->
            when {
                code == 429 -> AbsSignInError.TooManyAttempts
                code >= 500 -> AbsSignInError.ServerError(code)
                else -> AbsSignInError.NotAudiobookshelf
            }
        }) { api.getStatus(baseUrl) }
        // An empty list means a server that does not say; only a list without "local" is a no.
        if (status.authMethods.isNotEmpty() && "local" !in status.authMethods) {
            fail(AbsSignInError.LocalLoginOff)
        }
    }

    private suspend fun bookLibraries(
        baseUrl: String,
        token: String,
        whenHttp: (Int) -> AbsSignInError,
    ): List<AbsLibraryInfo> {
        val response = call(whenHttp) { api.getLibraries(baseUrl, "Bearer $token") }
        return response.libraries
            .filter { it.mediaType == "book" }
            .map { AbsLibraryInfo(it.id, it.name, it.mediaType) }
            .ifEmpty { fail(AbsSignInError.NoBookLibraries) }
    }

    private suspend fun <T> call(whenHttp: (Int) -> AbsSignInError, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw AbsSignInException(toError(t, whenHttp))
        }

    private fun toError(t: Throwable, whenHttp: (Int) -> AbsSignInError): AbsSignInError = when (t) {
        // Before IOException: HttpStatusException is one.
        is HttpStatusException -> whenHttp(t.status.value)
        is IOException -> AbsSignInError.Unreachable
        // Serialization, content conversion and similar decode failures: not an Audiobookshelf answer.
        else -> AbsSignInError.NotAudiobookshelf
    }

    private fun fail(error: AbsSignInError): Nothing = throw AbsSignInException(error)
}
