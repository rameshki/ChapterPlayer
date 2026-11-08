package com.chapterplayer.data.auth

import com.chapterplayer.data.model.LibraryInfo

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
}
