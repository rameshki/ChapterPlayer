package com.chapterplayer.domain.settings

import com.chapterplayer.data.sync.ServerSyncPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerStatusTextTest {

    private val minute = 60_000L

    @Test
    fun `time ago in words`() {
        assertEquals("just now", timeAgo(0))
        assertEquals("just now", timeAgo(59_000))
        assertEquals("1 min ago", timeAgo(minute))
        assertEquals("12 min ago", timeAgo(12 * minute + 30_000))
        assertEquals("1 h ago", timeAgo(60 * minute))
        assertEquals("23 h ago", timeAgo(23 * 60 * minute + 59 * minute))
        assertEquals("1 day ago", timeAgo(24 * 60 * minute))
        assertEquals("3 days ago", timeAgo(3 * 24 * 60 * minute))
        // A clock that went back counts as now.
        assertEquals("just now", timeAgo(-5 * minute))
    }

    @Test
    fun `the status line for each phase`() {
        val now = 100 * minute
        assertEquals(
            "Last synced 12 min ago · 342 books",
            serverStatusLine(ServerSyncPhase.Idle(lastSuccessAtMs = now - 12 * minute, bookCount = 342), now)
        )
        assertEquals("Last synced just now · 1 book", serverStatusLine(ServerSyncPhase.Idle(now, 1), now))
        assertEquals("Not synced yet", serverStatusLine(ServerSyncPhase.Idle(null, 0), now))
        assertEquals("Syncing…", serverStatusLine(ServerSyncPhase.Syncing, now))
        assertEquals(
            "Sync failed: Could not reach the server: timeout",
            serverStatusLine(ServerSyncPhase.Failed("Could not reach the server: timeout", now), now)
        )
    }

    @Test
    fun `a long failure reason is cut short`() {
        val line = serverStatusLine(ServerSyncPhase.Failed("x".repeat(300), 0), 0)
        assertEquals("Sync failed: ".length + 120, line.length)
        assertEquals('…', line.last())
    }
}
