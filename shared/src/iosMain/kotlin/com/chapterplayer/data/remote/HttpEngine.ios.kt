package com.chapterplayer.data.remote

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData

/**
 * Darwin on a session with no URL cache and no cookie handling: by default NSURLSession writes responses to the shared
 * on-disk cache in clear (Library/Caches), and plex.tv's resource list or Audiobookshelf's /api/me carry tokens.
 * Note: Darwin does not apply HttpTimeout's connect timeout (only request and socket ones); see step 5 for downloads.
 */
internal actual fun createHttpEngine(): HttpClientEngine = Darwin.create {
    configureSession {
        setURLCache(null)
        setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        setHTTPShouldSetCookies(false)
    }
}

internal actual fun httpDebugLog(message: String) {
    println("$LOG_TAG: $message")
}
