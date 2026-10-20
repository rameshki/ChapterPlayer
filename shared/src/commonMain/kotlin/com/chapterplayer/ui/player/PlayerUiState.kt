package com.chapterplayer.ui.player

import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.domain.playback.SleepTimer

/** What the full player screen shows. Every text is ready to display; none holds raw exception text. */
data class PlayerUiState(
    val nothingPlaying: Boolean = true,
    val title: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val chapterTitle: String? = null,
    /** Position in the whole book in ms (for the default bookmark name). */
    val positionMs: Long = 0L,
    val bookFraction: Float = 0f,
    /** Length of the whole book in ms; 0 when unknown (the sliders are then disabled). */
    val durationMs: Long = 0L,
    val chapterFraction: Float? = null,
    /** Book time played and left, as clock texts. */
    val elapsed: String = "0:00",
    val remaining: String = "0:00",
    val chapterElapsed: String? = null,
    val chapterRemaining: String? = null,
    val timeLeft: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Offer Pause (not Play): playing, or buffering with playback asked for. */
    val showPause: Boolean = false,
    val speed: Float = 1f,
    val speedLabel: String = "1x",
    val sleepTimer: SleepTimer = SleepTimer.Off,
    val sleepLabel: String = "Sleep timer",
    val chapters: List<ChapterRow> = emptyList(),
    val currentChapterIndex: Int? = null,
    /** True whenever the book has chapters: Previous restarts the current chapter, or goes to the one before. */
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false,
    val error: String? = null,
)
