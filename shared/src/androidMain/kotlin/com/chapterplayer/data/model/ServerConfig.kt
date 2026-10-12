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
 */
data class ServerConfig(
    val id: String,
    val source: ServerSource,
    val name: String,
    val baseUrl: String,
    val token: String,
    val libraryId: String? = null
)
