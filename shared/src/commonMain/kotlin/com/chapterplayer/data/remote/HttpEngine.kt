package com.chapterplayer.data.remote

import io.ktor.client.engine.HttpClientEngine

/**
 * A new engine for one HTTP client: OkHttp on Android, Darwin (NSURLSession) on iOS. Everything else about the clients
 * (timeouts, redirects, the logger that hides auth headers) is common, in [installApiDefaults] and its siblings.
 */
internal expect fun createHttpEngine(): HttpClientEngine

/** Where debug builds write one HTTP log line (headers only, auth headers hidden): Logcat on Android, the console on iOS. */
internal expect fun httpDebugLog(message: String)
