package com.chapterplayer.data.model

/**
 * Configuration for a connected media server.
 *
 * @param id       Local UUID — uniquely identifies this server config entry.
 * @param source   Whether this is an ABS or Plex server.
 * @param name     User-given display name (e.g. "Home ABS", "My Plex").
 * @param baseUrl  Base URL including protocol and port (e.g. "http://192.168.1.10:8080").
 * @param token    Auth token. For ABS this is the API key; for Plex it's the X-Plex-Token.
 * @param libraryId ABS library ID or Plex section ID to load books from.
 *                  Can be null if the user hasn't selected a library yet.
 * @param refreshToken Long-lived token used to get a new [token]; null when the server gave none.
 * @param excludedLibraryIds Libraries the user turned off for this server.
 * @param alternateBaseUrls For Plex: the server's other connections as plex.tv listed them (local, raw local address,
 *                  remote, relay). The token may go to these too, since they are the same server; [baseUrl] is the one in use.
 *                  Read from the connection store, never saved with the row.
 */
data class ServerConfig(
    val id: String,
    val source: ServerSource,
    val name: String,
    val baseUrl: String,
    val token: String,
    val libraryId: String? = null,
    val refreshToken: String? = null,
    val excludedLibraryIds: Set<String> = emptySet(),
    val alternateBaseUrls: List<String> = emptyList()
) {
    /** The stored token is gone or unreadable, so the user has to sign in again. */
    val needsSignIn: Boolean get() = token.isBlank()

    /** Left out so tokens can never reach a log. */
    override fun toString() =
        "ServerConfig(id=$id, source=$source, name=$name, baseUrl=$baseUrl, libraryId=$libraryId, " +
            "excluded=${excludedLibraryIds.size})"
}
