package com.chapterplayer.ui.browse

import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.merge.mergedBrowseItems
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.browse.BrowseItem
import kotlinx.coroutines.flow.Flow

/**
 * Every cached book with its reading progress and download state, in the repository's order, with a book that
 * several servers have as one entry (see `mergeDuplicates`). Finished books carry their progress too (isFinished =
 * true), so the filters and the finished check see them.
 * Cold, worked out by each collector: the app reads the shared `MergedLibrary` instead (one merge for every screen,
 * the car and the widgets); this stays for tests and one-off readers.
 */
fun observeBrowseItems(
    books: BookRepository,
    downloads: DownloadStates,
    merging: SourceMerging = SourceMerging.None,
): Flow<List<BrowseItem>> = mergedBrowseItems(books, downloads, merging)
