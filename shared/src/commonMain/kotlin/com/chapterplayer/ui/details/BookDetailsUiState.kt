package com.chapterplayer.ui.details

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.domain.browse.AboutInfo
import com.chapterplayer.domain.browse.BookLinks
import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.domain.browse.SeriesEntry

enum class DetailsTab { ABOUT, CHAPTERS, SERIES }

/** What the play control does for this book. */
enum class DetailsPlayback { NOT_PLAYING, STARTING, PLAYING, PAUSED }

enum class DetailsPhase { LOADING, LOADED, NOT_FOUND }

data class BookDetailsUiState(
    val phase: DetailsPhase = DetailsPhase.LOADING,
    val book: AudioBook? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val about: AboutInfo? = null,
    val series: List<SeriesEntry> = emptyList(),
    val links: BookLinks = BookLinks(null, emptyList(), emptyList()),
    /** "1 h 10 min left" when started, "Finished" when finished, null when not started. */
    val timeLeft: String? = null,
    val lengthText: String = "",
    val loadingDetails: Boolean = false,
    /** True once the server has answered with this book's details (then an empty chapter list is real). */
    val detailsLoaded: Boolean = false,
    val error: String? = null,
    val selectedTab: DetailsTab = DetailsTab.CHAPTERS,
    /** The play control's state for THIS book. */
    val playback: DetailsPlayback = DetailsPlayback.NOT_PLAYING,
    /** Playing is allowed: the book is downloaded, or the device is online and Downloaded only is off. */
    val canPlay: Boolean = false,
    /** Why [canPlay] is false, in words for the person; null when it is true. */
    val cannotPlayReason: String? = null,
    /** Why playback could not start or stopped, safe to show. */
    val playError: String? = null,
    /** Where this book is: the live position while it is loaded in the player, else the saved one. */
    val currentPositionMs: Long = 0L,
    /** Where Resume starts: the saved position, 0 for a finished book, -1 (the server decides) with no progress. */
    val resumePositionMs: Long = 0L,
    /** The download control; hidden for books that cannot be downloaded. */
    val download: DetailsDownload = DetailsDownload(),
    /** The copy the download control is about (another server's copy of a merged book can be the one). */
    val downloadBookId: String = "",
    /** "Plex · Home Plex" over the main control when a merged book's copy has a download; null otherwise. */
    val downloadCopyLabel: String? = null,
    /** A control for every other copy of a merged book that has a download (paused, failed, done...). */
    val otherCopyDownloads: List<CopyDownloadUi> = emptyList(),
    /** The copies of a book several servers have, downloaded first; empty for a book on one server. */
    val sources: List<SourceRow> = emptyList(),
    /** "available from Audiobookshelf and Plex"; null for one server. */
    val sourcesDescription: String? = null,
    /** The open "Play from" / "Download from" sheet; null when closed. */
    val sourceSheet: SourceSheetUi? = null,
    /** "Not the same book" is offered (a merged book). */
    val canSplit: Boolean = false,
    /** "Merge with" is offered. */
    val canMerge: Boolean = false,
    /** The open "Merge with" search; null when closed. */
    val mergeSheet: MergeSheetUi? = null,
    /** "Playing from Plex · Home Plex" while another copy of this book is in the player; null otherwise. */
    val playingFrom: String? = null,
    /** A short notice ("These books could not be shown as one"); null when there is none. */
    val notice: String? = null,
    /** The merged entry (null for a book on one server) and the merging inputs, for the actions. */
    val entry: com.chapterplayer.domain.browse.BrowseItem? = null,
    val mergeContext: com.chapterplayer.domain.browse.MergeContext? = null,
    /** The book is one of the person's favourites (any copy of a merged book is; see `isFavourite`). */
    val favourite: Boolean = false,
    /** The ids the heart marks or unmarks: every copy of the same entry [favourite] is worked out from. */
    val favouriteCopyIds: List<String> = emptyList(),
    /** "Mark as finished" or "Mark as not finished", from the entry's progress (the copy played last). */
    val mark: com.chapterplayer.domain.playback.MarkAction = com.chapterplayer.domain.playback.MarkAction.MarkFinished,
)
