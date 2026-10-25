package com.chapterplayer.domain.widgets

import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.resumeCandidate
import com.chapterplayer.domain.browse.entryContaining
import com.chapterplayer.domain.browse.hasCopy

/**
 * Whether the main activity acts on the Continue widget's Resume extra. Only a fresh tap counts: an activity
 * restored after process death, or relaunched from Recents, carries the old intent again and must not start a book.
 */
fun shouldHandleWidgetResume(restoredFromSavedState: Boolean, launchedFromHistory: Boolean): Boolean =
    !restoredFromSavedState && !launchedFromHistory

/**
 * Which book a Resume tap may start: the one the widget offers now, or Home's Resume book even when it cannot play
 * now (a widget drawn before the connection went), so the starter can say why. Any other id is refused: the
 * activity is exported.
 */
fun widgetMayResume(items: List<BrowseItem>, online: Boolean, downloadedOnly: Boolean, bookId: String): Boolean {
    // Any copy of the offered book or of Home's Resume book counts, so a primary that changed since the widget was
    // drawn (a download finished, a server came back) is not refused.
    val offered = continueWidgetState(items, online, downloadedOnly)
    if (offered is ContinueWidgetState.Book) {
        if (offered.bookId == bookId || entryContaining(items, offered.bookId)?.hasCopy(bookId) == true) return true
    }
    return resumeCandidate(items)?.entry?.hasCopy(bookId) == true
}
