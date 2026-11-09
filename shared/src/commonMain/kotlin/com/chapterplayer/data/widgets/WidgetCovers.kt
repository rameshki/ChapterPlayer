package com.chapterplayer.data.widgets

import com.chapterplayer.domain.widgets.CoverFetch

/**
 * Covers for the widgets, loaded in the app process before a widget is drawn (Android: `WidgetCoverCache`
 * over the authenticated cover loader, so no server address or token ever reaches the widget). [prefetch] loads the
 * cover of a book into the platform's memory cache: [CoverFetch.LOADED] when a cover was newly loaded (the refresher
 * redraws once), [CoverFetch.CACHED] when it was there already, [CoverFetch.FAILED] when there is none now. It may
 * suspend on the network.
 */
fun interface WidgetCovers {
    suspend fun prefetch(bookId: String): CoverFetch

    companion object {
        /** No covers (iOS and tests): the widget keeps its placeholder. */
        val None = WidgetCovers { CoverFetch.CACHED }
    }
}
