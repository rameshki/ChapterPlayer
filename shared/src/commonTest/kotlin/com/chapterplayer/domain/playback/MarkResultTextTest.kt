package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkResultTextTest {

    @Test
    fun `each outcome has its own wording`() {
        assertEquals("Marked as finished", markResultText(true, MarkOutcome.SYNCED))
        assertEquals("Marked as not finished", markResultText(false, MarkOutcome.LOCAL_ONLY))
        assertEquals("Marked as finished. It will reach the server when it is back.", markResultText(true, MarkOutcome.QUEUED))
        assertEquals("Marked as finished. Another copy of this book could not be changed.", markResultText(true, MarkOutcome.PARTIAL))
        assertEquals(
            "Marked as not finished. Another copy could not be changed; the rest reaches the servers when they are back.",
            markResultText(false, MarkOutcome.PARTIAL_QUEUED),
        )
    }
}
