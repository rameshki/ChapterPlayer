package com.chapterplayer.ui.browse

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.stateOf
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.mergeDuplicates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Every cached book with its reading progress and download state, in the repository's order, with a book that
 * several servers have as one entry (see `mergeDuplicates`). This is the one list Library, Home, Search, the details
 * page, the car and the widgets read, so they all show the same entries and the same primary copy.
 * Finished books carry their progress too (isFinished = true), so the filters and the finished check see them.
 */
fun observeBrowseItems(
    books: BookRepository,
    downloads: DownloadStates,
    merging: SourceMerging = SourceMerging.None,
): Flow<List<BrowseItem>> =
    combine(
        books.observeAllBooks(),
        books.observeAllProgress(),
        downloads.observe(),
        merging.context
    ) { allBooks, progress, downloadStates, context ->
        val progressById = progress.associateBy { it.bookId }
        val items = allBooks.map { book ->
            BrowseItem(book, progressById[book.id], downloadStates.stateOf(book.id))
        }
        if (context == null) items else mergeDuplicates(items, context)
    }
