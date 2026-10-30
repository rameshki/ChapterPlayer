package com.chapterplayer.data.remote

import android.util.Log
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createHttpEngine(): HttpClientEngine = OkHttp.create()

internal actual fun httpDebugLog(message: String) {
    Log.d(LOG_TAG, message)
}
