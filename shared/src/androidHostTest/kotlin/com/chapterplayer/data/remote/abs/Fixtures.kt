package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.abs.dto.AbsLibrariesResponse
import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemsResponse
import com.squareup.moshi.Moshi

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
    private val moshi = Moshi.Builder().build()

    fun libraries(json: String): AbsLibrariesResponse =
        checkNotNull(moshi.adapter(AbsLibrariesResponse::class.java).fromJson(json))

    fun items(json: String): AbsLibraryItemsResponse =
        checkNotNull(moshi.adapter(AbsLibraryItemsResponse::class.java).fromJson(json))
}
