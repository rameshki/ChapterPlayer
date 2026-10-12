package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.abs.dto.AbsLibrariesResponse
import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemsResponse

/** Reads a recorded server response from src/androidHostTest/resources/fixtures. */
internal fun fixture(path: String): String {
    val url = checkNotNull(Thread.currentThread().contextClassLoader.getResource("fixtures/$path")) {
        "Missing test fixture fixtures/$path"
    }
    return url.readText(Charsets.UTF_8)
}

/**
 * The parsing entry points the tests go through. Tests only call these two functions, so
 * swapping the JSON library only changes this file.
 */
internal object AbsParsers {
    fun libraries(json: String): AbsLibrariesResponse = ApiJson.decodeFromString(json)

    fun items(json: String): AbsLibraryItemsResponse = ApiJson.decodeFromString(json)
}
