package com.chapterplayer.data.remote

/** The platform Plex playback reports name (`X-Plex-Platform`): "Android" or "iOS". */
internal expect val PLEX_PLATFORM_NAME: String

/**
 * The device id sent when an Audiobookshelf playback session opens. The app sends no per-install id, so one constant
 * per platform names the client.
 */
internal expect val ABS_DEVICE_ID: String

/** The generic device name Jellyfin is told (`Device="..."`): "Android" or "iPhone", never a personal name. */
internal expect val JELLYFIN_DEVICE_NAME: String
