package com.chapterplayer.data.model

import com.chapterplayer.data.remote.ApiJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable

/**
 * Reads and writes the chapter list stored as text in the books table. Existing rows were
 * written by earlier versions of the app, so the format is pinned by ChapterJsonTest.
 */
internal object ChapterJson {
    private val serializer = ListSerializer(Chapter.serializer())

    fun encode(chapters: List<Chapter>): String = ApiJson.encodeToString(serializer, chapters)

    fun decode(json: String): List<Chapter> =
        ApiJson.decodeFromString(serializer.nullable, json) ?: emptyList()
}
