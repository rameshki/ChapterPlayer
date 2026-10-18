package com.chapterplayer.ui.browse

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.stateOf
import com.chapterplayer.domain.browse.BrowseItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Every cached book with its reading progress and download state, in the repository's order.
 * Finished books carry their progress too (isFinished = true), so the filters and the finished check see them.
 */
fun observeBrowseItems(books: BookRepository, downloads: DownloadStates): Flow<List<BrowseItem>> =
    combine(
        books.observeAllBooks(),
        books.observeAllProgress(),
        downloads.observe()
    ) { allBooks, progress, downloadStates ->
        val progressById = progress.associateBy { it.bookId }
        allBooks.map { book ->
            BrowseItem(book, progressById[book.id], downloadStates.stateOf(book.id))
        }
    }
