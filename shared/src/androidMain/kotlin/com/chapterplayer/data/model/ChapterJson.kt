package com.chapterplayer.data.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Reads and writes the chapter list stored as text in the books table. Existing rows were
 * written by earlier versions of the app, so the format is pinned by ChapterJsonTest.
 */
internal object ChapterJson {
    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<Chapter>>(Types.newParameterizedType(List::class.java, Chapter::class.java))

    fun encode(chapters: List<Chapter>): String = adapter.toJson(chapters)

    fun decode(json: String): List<Chapter> = adapter.fromJson(json) ?: emptyList()
}
