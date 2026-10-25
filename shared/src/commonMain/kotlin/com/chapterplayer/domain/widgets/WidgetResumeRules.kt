package com.chapterplayer.domain.widgets

import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.resumeCandidate

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
    val offered = continueWidgetState(items, online, downloadedOnly)
    if (offered is ContinueWidgetState.Book && offered.bookId == bookId) return true
    return resumeCandidate(items)?.item?.book?.id == bookId
}
