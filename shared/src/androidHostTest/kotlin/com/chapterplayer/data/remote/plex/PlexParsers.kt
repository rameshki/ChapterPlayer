package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.plex.dto.PlexLibraryAllResponse
import com.chapterplayer.data.remote.plex.dto.PlexLibrarySectionsResponse
import com.chapterplayer.data.remote.plex.dto.PlexPinDto
import com.chapterplayer.data.remote.plex.dto.PlexResourceDto

/**
 * The parsing entry points the Plex tests go through. Tests only call these functions, so
 * swapping the JSON library only changes this file.
 */
internal object PlexParsers {
    fun pin(json: String): PlexPinDto = ApiJson.decodeFromString(json)

    fun resources(json: String): List<PlexResourceDto> = ApiJson.decodeFromString(json)

    fun sections(json: String): PlexLibrarySectionsResponse = ApiJson.decodeFromString(json)

    fun libraryItems(json: String): PlexLibraryAllResponse = ApiJson.decodeFromString(json)
}
