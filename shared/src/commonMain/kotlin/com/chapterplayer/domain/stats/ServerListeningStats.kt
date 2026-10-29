package com.chapterplayer.domain.stats

/**
 * A server's own listening history: seconds per local day ([days], keyed by epoch day), seconds per book over all
 * time ([items], keyed by the app's book id) and the all-time total. Audiobookshelf keeps this; Plex has none.
 */
data class ServerListeningStats(
    val days: Map<Int, Long> = emptyMap(),
    val items: Map<String, Long> = emptyMap(),
    val totalSeconds: Long = 0L,
)
