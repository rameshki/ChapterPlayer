package com.chapterplayer.data.merge

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.stateOf
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.MergeGroups
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.duplicateGroups
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn

/** How often the merged list was worked out, for tests: [groupings] = the costly grouping, [lists] = every list built. */
class MergeRuns {
    @Volatile var groupings: Int = 0
        internal set
    @Volatile var lists: Int = 0
        internal set
}

/**
 * Every cached book with its reading progress and download state, in the repository's order, with a book that several
 * servers have as one entry (see `mergeDuplicates`). Cold: each collector works it out on its own. The grouping (which
 * books are one entry) is kept while the books and the corrections stay the same, so a progress save (every 10 s while
 * a book plays), a download change or a source choice only rebuilds the entries, never the grouping.
 */
fun mergedBrowseItems(
    books: BookRepository,
    downloads: DownloadStates,
    merging: SourceMerging = SourceMerging.None,
    runs: MergeRuns? = null,
): Flow<List<BrowseItem>> = flow {
    // One per collection, so nothing is kept once nobody collects.
    var lastBooks: List<AudioBook>? = null
    var lastOverrides: MergeOverrides? = null
    var lastGroups: MergeGroups? = null
    fun groupsFor(allBooks: List<AudioBook>, overrides: MergeOverrides): MergeGroups {
        val cached = lastGroups
        if (cached != null && overrides == lastOverrides && (allBooks === lastBooks || allBooks == lastBooks)) return cached
        runs?.let { it.groupings++ }
        return duplicateGroups(allBooks, overrides).also {
            lastBooks = allBooks
            lastOverrides = overrides
            lastGroups = it
        }
    }
    emitAll(
        combine(
            books.observeAllBooks(),
            books.observeAllProgress(),
            downloads.observe(),
            merging.context
        ) { allBooks, progress, downloadStates, context ->
            runs?.let { it.lists++ }
            val progressById = progress.associateBy { it.bookId }
            val items = allBooks.map { book ->
                BrowseItem(book, progressById[book.id], downloadStates.stateOf(book.id))
            }
            if (context == null || items.size < 2) items
            else groupsFor(allBooks, context.overrides).entries(items, context.playChoice, context.onlineServerIds)
        }
    )
}

/**
 * The one merged library of the app (Library, Home, Search, group pages, details, the player menu, Up next, stats,
 * Diagnostics, the widgets, the car and the Up next advancer all read [items]): worked out once per change on
 * [dispatcher] and shared by every reader while one collects. Without [scope] (tests, a view model built by hand)
 * nothing is shared: each collector works it out itself, in its own context.
 *
 * The share stops [STOP_TIMEOUT_MS] after the last reader leaves and then forgets its value, so a reader that comes
 * later (a `first()` from the car or a widget) never starts from an old list; while someone collects, the value it
 * replays is the live one.
 */
class MergedLibrary(
    books: BookRepository,
    downloads: DownloadStates,
    merging: SourceMerging = SourceMerging.None,
    scope: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** How often the list was worked out (tests). */
    val runs = MergeRuns()

    private val cold: Flow<List<BrowseItem>> = mergedBrowseItems(books, downloads, merging, runs)

    /**
     * The merged list; it emits on every change of the books, progress, downloads or merging context. Unshared, it runs
     * in the collector's context (as the cold flow always did), so a view model's own dispatcher applies.
     */
    val items: Flow<List<BrowseItem>> = scope?.let {
        cold.flowOn(dispatcher)
            .shareIn(it, SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)
    } ?: cold

    companion object {
        /** How long the share keeps running after the last reader left (a screen change, a rotation). */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
