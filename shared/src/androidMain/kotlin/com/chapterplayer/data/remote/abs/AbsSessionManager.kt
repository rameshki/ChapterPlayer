package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.security.jwtExpiryEpochSeconds
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Runs a call to an Audiobookshelf server with a valid access token. */
interface AbsAuthorizer {
    /** [call] gets the full header value, "Bearer <token>". */
    suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T
}

/**
 * Keeps the Audiobookshelf sign-in alive: refreshes the access token shortly before it expires,
 * or once after a 401, and saves the new tokens. Refreshes are serialized per server, because a
 * refresh token is used up when it is traded. Tokens are never logged or put in a message.
 */
class AbsSessionManager(
    private val api: AbsApiService,
    private val servers: ServerRepository,
    /** Epoch seconds. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : AbsAuthorizer {

    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T {
        if (server.token.isBlank()) throw SignInRequiredException(server.name)

        var token = server.token
        if (server.refreshToken != null && expiresSoon(token)) {
            token = refresh(server, failedToken = token)
        }
        try {
            return call("Bearer $token")
        } catch (e: HttpStatusException) {
            if (e.status.value != 401 || server.refreshToken == null) {
                if (e.status.value == 401) throw SignInRequiredException(server.name)
                throw e
            }
        }
        val renewed = refresh(server, failedToken = token)
        try {
            return call("Bearer $renewed")
        } catch (e: HttpStatusException) {
            if (e.status.value == 401) throw SignInRequiredException(server.name)
            throw e
        }
    }

    private fun expiresSoon(token: String): Boolean {
        val exp = jwtExpiryEpochSeconds(token) ?: return false // API keys and legacy tokens: never ahead of time
        return exp - clock() <= REFRESH_AHEAD_SECONDS
    }

    /** Returns the access token to use after [failedToken] was found expired or refused. */
    private suspend fun refresh(server: ServerConfig, failedToken: String): String =
        locks.computeIfAbsent(server.id) { Mutex() }.withLock {
            val current = servers.getById(server.id) ?: throw SignInRequiredException(server.name)
            if (current.token.isBlank()) throw SignInRequiredException(server.name)
            // Someone else refreshed while this call waited for the lock.
            if (current.token != failedToken) return@withLock current.token
            val refreshToken = current.refreshToken ?: throw SignInRequiredException(server.name)

            val user = requestRefresh(current, refreshToken)
            val newToken = (user.accessToken ?: user.token)?.takeIf { it.isNotBlank() }
                ?: throw SignInRequiredException(server.name)
            servers.save(current.copy(token = newToken, refreshToken = user.refreshToken ?: refreshToken))
            newToken
        }

    private suspend fun requestRefresh(server: ServerConfig, refreshToken: String) =
        try {
            api.refresh(server.baseUrl, refreshToken).user
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) { // before IOException: it is one
            if (e.status.value == 401 || e.status.value == 403) throw SignInRequiredException(server.name)
            throw e
        } catch (e: IOException) {
            throw e // offline or similar: the sign-in may still be good
        } catch (_: SerializationException) {
            throw unexpectedAnswer()
        } catch (_: ContentConvertException) {
            throw unexpectedAnswer()
        } catch (_: NoTransformationFoundException) {
            throw unexpectedAnswer()
        } catch (_: IllegalArgumentException) {
            throw unexpectedAnswer()
        }

    // No cause and no text: a decode error message can hold a slice of the response, which holds tokens.
    private fun unexpectedAnswer() = IOException("The server sent an unexpected answer")

    private companion object {
        const val REFRESH_AHEAD_SECONDS = 60L
    }
}
