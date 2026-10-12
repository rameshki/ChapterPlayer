package com.chapterplayer.data.remote

import kotlinx.serialization.json.Json

/**
 * The JSON settings for every server response. Servers add fields freely, so unknown keys are
 * ignored, and numbers and text are read leniently (a Plex id may arrive as a number).
 */
internal val ApiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
