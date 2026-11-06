package com.chapterplayer.domain.covers

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.car.CarTrust
import com.chapterplayer.domain.car.isTrustedCarController
import com.chapterplayer.domain.plex.PLEX_ID_PREFIX
import com.chapterplayer.domain.plex.plexTokenFor

/*
 * Covers outside the app's own screens: the media notification, the lock screen and Android Auto. Those readers get a
 * cover through the app (artwork the player's session loads, or a content address served by the app), never the
 * server's address, so no token reaches a URI, media metadata or another app.
 */

/** The longest side a cover handed to the system or the car is scaled to. */
const val COVER_MAX_SIDE = 512

/** JPEG quality of a scaled cover. */
const val COVER_JPEG_QUALITY = 85

/** The biggest cover the app downloads for the notification or the car (a larger answer is not a cover). */
const val COVER_MAX_BYTES = 8L * 1024 * 1024

/** A cover's size in pixels. */
data class CoverSize(val width: Int, val height: Int)

/**
 * The power-of-two sample size to decode a [width] x [height] image with: the largest that still leaves the long side
 * at least [maxSide], so decoding needs little memory and the last scale only shrinks. 1 for a small or unknown image.
 */
fun coverSampleSize(width: Int, height: Int, maxSide: Int = COVER_MAX_SIDE): Int {
    val long = maxOf(width, height)
    if (long <= 0 || maxSide <= 0) return 1
    var sample = 1
    while (long / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** The size to scale a [width] x [height] image to: the long side at most [maxSide], the shape kept. Null for no image. */
fun coverTargetSize(width: Int, height: Int, maxSide: Int = COVER_MAX_SIDE): CoverSize? {
    if (width <= 0 || height <= 0 || maxSide <= 0) return null
    val long = maxOf(width, height)
    if (long <= maxSide) return CoverSize(width, height)
    fun scaled(side: Int): Int = ((side.toLong() * maxSide + long / 2) / long).toInt().coerceAtLeast(1)
    return if (width >= height) CoverSize(maxSide, scaled(height)) else CoverSize(scaled(width), maxSide)
}

private const val ABS_BOOK_PREFIX = "abs_"
private const val MAX_ID_LENGTH = 128
private const val COVER_PATH = "cover"

/**
 * Whether [id] is a book id a cover may be asked for: `abs_` or `plex_` and up to 128 letters, digits, `_` or `-`.
 * Nothing else (no dots, slashes, percent signs or spaces), so an id can never walk out of a path.
 */
fun isCoverBookId(id: String): Boolean {
    val rest = when {
        id.startsWith(ABS_BOOK_PREFIX) -> id.substring(ABS_BOOK_PREFIX.length)
        id.startsWith(PLEX_ID_PREFIX) -> id.substring(PLEX_ID_PREFIX.length)
        else -> return false
    }
    return rest.length in 1..MAX_ID_LENGTH && rest.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
}

/** The authority of the app's cover provider: `<applicationId>.covers`. */
fun coverAuthority(applicationId: String): String = "$applicationId.covers"

/** `content://<authority>/cover/<bookId>`: what the car and the session get for a book's cover. Null for a bad id. */
fun coverContentUri(authority: String, bookId: String): String? =
    if (isCoverBookId(bookId)) "content://$authority/$COVER_PATH/$bookId" else null

/**
 * The book a cover address names, or null when [uri] is anything but `content://<authority>/cover/<bookId>` exactly:
 * another scheme or authority, another path, a query or fragment, an encoded character or a bad id.
 */
fun parseCoverUri(uri: String, authority: String): String? {
    val prefix = "content://$authority/$COVER_PATH/"
    if (!uri.startsWith(prefix)) return null
    val id = uri.substring(prefix.length)
    return id.takeIf(::isCoverBookId)
}

/**
 * Whether a caller whose user id holds [callerPackages] may read covers: this app or one of the trusted car packages
 * ([isTrustedCarController], the same rule as browsing), matched exactly. Unknown callers are refused.
 */
fun coverCallerAllowed(callerPackages: List<String>?, ownPackage: String): Boolean {
    if (ownPackage.isBlank() || callerPackages.isNullOrEmpty()) return false
    return callerPackages.any { isTrustedCarController(it, ownPackage) != CarTrust.NONE }
}

/** How a cover request is authorised. The token itself is read when the request is made, never kept here. */
enum class CoverAuth { ABS_BEARER, PLEX_TOKEN, NONE }

/**
 * How to ask for the cover at [url] of a book on [server], or null when it must not be asked at all: the address is not
 * under the server's own address (an Audiobookshelf server's base address; for Plex, its address in use or one of its
 * verified connections, as for every Plex cover), or a Plex server has no token to send.
 */
fun coverRequestAuth(url: String, server: ServerConfig): CoverAuth? = when (server.source) {
    ServerSource.PLEX -> if (plexTokenFor(url, listOf(server)) != null) CoverAuth.PLEX_TOKEN else null
    else -> when {
        server.baseUrl.isBlank() || !url.startsWith(server.baseUrl.trimEnd('/') + "/") -> null
        server.token.isBlank() -> CoverAuth.NONE
        else -> CoverAuth.ABS_BEARER
    }
}
