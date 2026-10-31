package com.chapterplayer.data.playback

/** One error of an Apple error chain (the error and its underlying errors): its domain and code. No message. */
data class PlatformErrorCode(val domain: String, val code: Long)

private const val NSURL_ERROR_DOMAIN = "NSURLErrorDomain"
private const val CORE_MEDIA_ERROR_DOMAIN = "CoreMediaErrorDomain"

// NSURLErrorTimedOut, CannotFindHost, CannotConnectToHost, NetworkConnectionLost, DNSLookupFailed,
// NotConnectedToInternet, InternationalRoamingOff, CallIsActive, DataNotAllowed, SecureConnectionFailed.
private val NETWORK_CODES = setOf(-1001L, -1003L, -1004L, -1005L, -1006L, -1009L, -1018L, -1019L, -1020L, -1200L)

// NSURLErrorUserCancelledAuthentication and UserAuthenticationRequired: what a refused token turns into.
private val AUTH_CODES = setOf(-1012L, -1013L)

/**
 * Why AVPlayer stopped an item, from its error [chain] and the HTTP status its error log names ([httpStatus], if any).
 * Only for choosing the words: the controller checks the address again with its own request before it gives up, and that
 * check knows the real answer. CoreMedia's -12938 is its "HTTP 404"; its other HTTP codes are not mapped (unverified).
 */
fun classifyAppleMediaError(chain: List<PlatformErrorCode>, httpStatus: Int?): EngineFailure = when {
    httpStatus == 401 || httpStatus == 403 -> EngineFailure.UNAUTHORIZED
    httpStatus == 404 || httpStatus == 410 -> EngineFailure.NOT_FOUND
    chain.any { it.domain == NSURL_ERROR_DOMAIN && it.code in AUTH_CODES } -> EngineFailure.UNAUTHORIZED
    chain.any { it.domain == NSURL_ERROR_DOMAIN && it.code in NETWORK_CODES } -> EngineFailure.NETWORK
    chain.any { it.domain == CORE_MEDIA_ERROR_DOMAIN && it.code == -12938L } -> EngineFailure.NOT_FOUND
    else -> EngineFailure.OTHER
}
