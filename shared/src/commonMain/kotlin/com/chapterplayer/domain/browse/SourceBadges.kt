package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.ServerSource

/** The source badge tells books apart only when books of two or more kinds of server can sit in the same list. */
fun showSourceBadges(sources: Collection<ServerSource>): Boolean = sources.toSet().size >= 2
