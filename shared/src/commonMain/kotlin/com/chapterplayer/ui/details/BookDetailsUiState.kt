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
    val resumePositionMs: Long = 0L
)
