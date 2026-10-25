package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.ServerSource

/** The source badge tells books apart only when Audiobookshelf and Plex books can sit in the same list. */
fun showSourceBadges(sources: Collection<ServerSource>): Boolean =
    ServerSource.ABS in sources && ServerSource.PLEX in sources
