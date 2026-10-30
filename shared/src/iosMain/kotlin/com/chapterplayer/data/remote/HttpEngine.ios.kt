package com.chapterplayer.data.remote

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun createHttpEngine(): HttpClientEngine = Darwin.create()

internal actual fun httpDebugLog(message: String) {
    println("$LOG_TAG: $message")
}
