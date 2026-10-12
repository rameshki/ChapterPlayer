package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.plex.dto.PlexLibraryAllResponse
import com.chapterplayer.data.remote.plex.dto.PlexLibrarySectionsResponse
import com.chapterplayer.data.remote.plex.dto.PlexPinDto
import com.chapterplayer.data.remote.plex.dto.PlexResourceDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * The parsing entry points the Plex tests go through. Tests only call these functions, so
 * swapping the JSON library only changes this file.
 */
internal object PlexParsers {
    private val moshi = Moshi.Builder().build()

    fun pin(json: String): PlexPinDto =
        checkNotNull(moshi.adapter(PlexPinDto::class.java).fromJson(json))

    fun resources(json: String): List<PlexResourceDto> {
        val type = Types.newParameterizedType(List::class.java, PlexResourceDto::class.java)
        return checkNotNull(moshi.adapter<List<PlexResourceDto>>(type).fromJson(json))
    }

    fun sections(json: String): PlexLibrarySectionsResponse =
        checkNotNull(moshi.adapter(PlexLibrarySectionsResponse::class.java).fromJson(json))

    fun libraryItems(json: String): PlexLibraryAllResponse =
        checkNotNull(moshi.adapter(PlexLibraryAllResponse::class.java).fromJson(json))
}
