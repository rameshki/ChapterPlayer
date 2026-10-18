package com.chapterplayer.ui.details

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.domain.browse.AboutInfo
import com.chapterplayer.domain.browse.BookLinks
import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.domain.browse.SeriesEntry

enum class DetailsTab { ABOUT, CHAPTERS, SERIES }

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
    val error: String? = null,
    val selectedTab: DetailsTab = DetailsTab.CHAPTERS,
    /** Playback arrives in steps 6-7. */
    val resumeEnabled: Boolean = false
)
