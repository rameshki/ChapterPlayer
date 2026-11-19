package com.chapterplayer.data.auth

import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.domain.jellyfin.QuickConnectPoll

/** Why a Jellyfin sign-in failed. The words for each are in `domain/jellyfin/JellyfinTexts.kt`. */
sealed interface JellyfinSignInError {
    data object Unreachable : JellyfinSignInError
    /** The address answered as something else (a web page, a proxy's login, another product). */
    data object NotJellyfin : JellyfinSignInError
    data class TooOld(val version: String) : JellyfinSignInError
    data object WrongCredentials : JellyfinSignInError
    data object NoBooksLibrary : JellyfinSignInError
    /** Sign in again found another server (another `Id`) at the stored address. */
    data object OtherServer : JellyfinSignInError
    data class ServerError(val code: Int) : JellyfinSignInError
    /** The address typed is empty or not an http(s) address. */
    data object InvalidAddress : JellyfinSignInError
    /** The address answered with a redirect (http to https, a path, a login page): nothing is followed. */
    data object Redirected : JellyfinSignInError
    /** The secure connection failed (a certificate problem). */
    data object SecureConnection : JellyfinSignInError

    /** Quick Connect is switched off on the server (the check said so, or Initiate was refused). */
    data object QuickConnectOff : JellyfinSignInError

    /** The server refused the Quick Connect sign-in: the code was not approved (or no longer is). */
    data object QuickConnectNotApproved : JellyfinSignInError
}

/** The only exception a Jellyfin sign-in throws. The message is fixed so no server text, password or token leaks. */
class JellyfinSignInException(val error: JellyfinSignInError) : Exception("Sign-in failed")

/** A successful sign-in. [token] is the credential: [toString] leaves it out. */
data class JellyfinSignInResult(
    val token: String,
    /** The server's own `Id` (kept to check the address on Sign in again). */
    val serverId: String,
    val userId: String,
    val version: String,
    /** The books views, with the server row id as their `serverId`. */
    val libraries: List<LibraryInfo>,
) {
    override fun toString() =
        "JellyfinSignInResult(serverId=$serverId, userId=$userId, version=$version, libraries=${libraries.size})"
}

/**
 * A Quick Connect request the server is holding: [secret] is a credential (kept in memory only, sent in the query of the
 * waiting reads and in the final sign-in) and [code] is what the person types on the other device. [toString] leaves both
 * out.
 */
class QuickConnectRequest(val secret: String, val code: String) {
    override fun toString() = "QuickConnectRequest()"
}

interface JellyfinSignInPort {
    /**
     * Signs in to the Jellyfin server at [address] for the server row [serverRowId] (its DeviceId is built from it, so
     * the same id must be used when the row is saved). With [expectedServerId] (Sign in again) a server with another
     * `Id` is refused before the password is sent. Stores nothing. Throws only [JellyfinSignInException].
     */
    suspend fun withPassword(
        address: String,
        serverRowId: String,
        username: String,
        password: String,
        expectedServerId: String? = null,
    ): JellyfinSignInResult

    /**
     * Quick Connect, step 1 (J3): checks the address like [withPassword] (a Jellyfin server new enough, and with
     * [expectedServerId] the same server), asks whether Quick Connect is on and starts a request. Throws only
     * [JellyfinSignInException]; [JellyfinSignInError.QuickConnectOff] when it is off.
     */
    suspend fun startQuickConnect(address: String, serverRowId: String, expectedServerId: String? = null): QuickConnectRequest =
        throw JellyfinSignInException(JellyfinSignInError.Unreachable)

    /** Step 2: one read of the request. Never throws except for cancellation; a failed read is [QuickConnectPoll.UNREACHABLE]. */
    suspend fun pollQuickConnect(address: String, serverRowId: String, request: QuickConnectRequest): QuickConnectPoll =
        QuickConnectPoll.UNREACHABLE

    /**
     * Step 3, after an approval: signs in with the secret and finishes like [withPassword] (identity check, the user's
     * views, the books libraries). [JellyfinSignInError.QuickConnectNotApproved] when the server refuses. Stores nothing.
     */
    suspend fun finishQuickConnect(
        address: String,
        serverRowId: String,
        request: QuickConnectRequest,
        expectedServerId: String? = null,
    ): JellyfinSignInResult = throw JellyfinSignInException(JellyfinSignInError.Unreachable)
}
