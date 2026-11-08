package com.chapterplayer.data.widgets

/**
 * Covers for the Listen widget, loaded in the app process before the widget is drawn (Android: `ListenCoverCache`
 * over the authenticated cover loader, so no server address or token ever reaches the widget). [prefetch] loads the
 * cover of a book into the platform's memory cache and returns true only when a cover was newly loaded, so the
 * refresher redraws once; a cached cover, no cover or a failure return false. It may suspend on the network.
 */
fun interface WidgetCovers {
    suspend fun prefetch(bookId: String): Boolean

    companion object {
        /** No covers (iOS and tests): the widget keeps its placeholder. */
        val None = WidgetCovers { false }
    }
}
