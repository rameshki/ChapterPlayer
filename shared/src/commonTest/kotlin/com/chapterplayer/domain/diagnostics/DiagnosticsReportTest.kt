package com.chapterplayer.domain.diagnostics

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.SourceChoice
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsReportTest {

    private val now = 1_793_628_185_000L // 2026-11-02 14:03:05 UTC

    @Test fun `utc times are readable`() {
        assertEquals("2026-11-02 14:03:05 UTC", formatUtc(now))
        assertEquals("1970-01-01 00:00:01 UTC", formatUtc(1000L))
        assertEquals("2024-02-29 23:59:59 UTC", formatUtc(1_709_251_199_000L))
        assertEquals("never", formatUtc(0L))
    }

    @Test fun `clocks and sizes are short`() {
        assertEquals("1:02:03", formatClock(3_723_000L))
        assertEquals("5:07", formatClock(307_000L))
        assertEquals("12.3 MB", formatBytes(12_900_000L))
        assertEquals("900 B", formatBytes(900L))
    }

    private val local = ReadingProgress("li_abc123def456ghi789jk", 3_723_000L, 36_000_000L, now - 60_000L, isFinished = false, mirrored = true)

    private fun absCopy(server: ServerReadState = ServerReadState.NotRead) = DiagCopy(
        bookId = "li_abc123def456ghi789jk", serverName = "Home ABS", sourceLabel = "Audiobookshelf", isPlex = false,
        local = local, server = server,
        pending = listOf(
            PendingProgress("li_abc123def456ghi789jk", "s1", 3_000_000L, 36_000_000L, 12_000L, now - 5_000L, attempts = 2,
                nextAttemptAtMs = now + 30_000L, markedFinished = null, mirrorOf = "plex_48213")
        ),
        download = DiagDownload("DONE", 5_000_000L, 5_000_000L, listOf("01.mp3", "02.mp3"), "li_abc123def456ghi789jk", null),
    )

    @Test fun `a report lists local server pending and download facts`() {
        val read = ServerReadState.Read(ServerProgressReading(ServerProgress(3_600_000L, 36_000_000L, false, now - 1000L)), now)
        val text = buildReport(DiagnosticsInput("A Book", "An Author", listOf(absCopy(read)), null, now))
        assertTrue(text.contains("Book: A Book by An Author"))
        assertTrue(text.contains("Generated: 2026-11-02 14:03:05 UTC"))
        assertTrue(text.contains("== Copy 1: Home ABS (Audiobookshelf) =="))
        assertTrue(text.contains("Book id: li_abc123def456ghi789jk"))
        assertTrue(text.contains("Local progress: position 1:02:03 (3723 s) of 10:00:00"))
        assertTrue(text.contains("finished no, mirrored yes"))
        assertTrue(text.contains("Position 1:00:00 (3600 s) of 10:00:00, finished no"))
        assertTrue(text.contains("attempts 2"))
        assertTrue(text.contains("markedFinished none, mirrorOf plex_48213"))
        assertTrue(text.contains("Download: DONE, 4.8 MB of 4.8 MB, folder li_abc123def456ghi789jk, files 2: 01.mp3, 02.mp3"))
    }

    @Test fun `not read and failed states are worded`() {
        val notRead = buildReport(DiagnosticsInput("T", "", listOf(absCopy()), null, now))
        assertTrue(notRead.contains("Server progress: not read yet"))
        val failed = buildReport(DiagnosticsInput("T", "", listOf(absCopy(ServerReadState.Failed("Sign in again in Settings"))), null, now))
        assertTrue(failed.contains("Server progress: read failed: Sign in again in Settings"))
    }

    @Test fun `plex tracks show a key suffix only`() {
        val tracks = listOf(
            PlexTrackProgress("987654321", 1_800_000L, 600_000L, 1, 1_793_000_000L),
            PlexTrackProgress("987654322", 1_800_000L, null, 0, null),
        )
        val copy = absCopy(ServerReadState.Read(ServerProgressReading(ServerProgress(2_400_000L, 3_600_000L, false, now), tracks), now))
            .copy(isPlex = true, bookId = "plex_48213", sourceLabel = "Plex", pending = emptyList(), download = null)
        val text = buildReport(DiagnosticsInput("T", "A", listOf(copy), null, now))
        assertTrue(text.contains("Computed book position 40:00"))
        assertTrue(text.contains("Track 1 (key ...321): offset 10:00, plays 1"))
        assertTrue(text.contains("Track 2 (key ...322): offset none, plays 0, last viewed never"))
        assertFalse(text.contains("987654321"))
        assertTrue(text.contains("Pending queue: empty"))
        assertTrue(text.contains("Download: none"))
    }

    @Test fun `secrets and addresses in any field never reach the report`() {
        val bad = absCopy(ServerReadState.Failed("HTTP 500 from https://abs.myhome.example.org:13378/api/me/progress/li_abc?token=sD3fG9hJkL2mN5pQ7rT1"))
            .copy(
                serverName = "192.168.1.20",
                download = DiagDownload("FAILED", 0, 10, listOf("a.mp3"), "li_abc123def456ghi789jk",
                    "Connection to my-nas.local:443 refused, Authorization: Bearer abcdef0123456789abcdef"),
            )
        val text = buildReport(DiagnosticsInput("A Book", "An Author", listOf(bad), null, now))
        for (secret in listOf("sD3fG9hJkL2mN5pQ7rT1", "myhome.example.org", "192.168.1.20", "my-nas", "abcdef0123456789abcdef", "13378")) {
            assertFalse(text.contains(secret), "'$secret' survived in:\n$text")
        }
        assertTrue(text.contains("Book id: li_abc123def456ghi789jk"))
    }

    // ---- the source rules ----

    private fun item(id: String, serverId: String, source: ServerSource, download: DownloadState = DownloadState.NOT_DOWNLOADED, played: Long? = null) =
        BrowseItem(
            AudioBook(id, "Same", "Same", "", 36_000_000L, emptyList(), "", null, source, serverId),
            played?.let { ReadingProgress(id, 1000L, 36_000_000L, it) }, download
        )

    private fun entry(a: BrowseItem, b: BrowseItem) = a.copy(sources = listOf(a, b))
    private val names = mapOf("abs" to "Home ABS", "plex" to "Den Plex")

    @Test fun `a single copy is itself`() {
        val a = item("li_1", "abs", ServerSource.ABS)
        val m = describeMerge(a, MergeContext(), names)
        assertEquals(listOf("li_1"), m.memberBookIds)
        assertTrue(m.playPick.startsWith("this copy"))
        assertTrue(m.downloadPick.startsWith("this copy"))
    }

    @Test fun `ask outcomes say why`() {
        val e = entry(item("li_1", "abs", ServerSource.ABS), item("plex_2", "plex", ServerSource.PLEX))
        val m = describeMerge(e, MergeContext(onlineServerIds = setOf("abs", "plex")), names)
        assertEquals("Ask every time", m.playChoice)
        assertTrue(m.playPick.startsWith("asks (Play from is Ask every time"))
        assertTrue(m.downloadPick.startsWith("asks (Download from is Ask every time"))
    }

    @Test fun `a chosen reachable copy is picked and an offline one asks`() {
        val e = entry(item("li_1", "abs", ServerSource.ABS), item("plex_2", "plex", ServerSource.PLEX))
        val ctx = MergeContext(
            playChoice = SourceChoice.OfType(ServerSource.PLEX), downloadChoice = SourceChoice.OfServer("abs"),
            onlineServerIds = setOf("abs", "plex")
        )
        val ok = describeMerge(e, ctx, names)
        assertTrue(ok.playPick.startsWith("Den Plex (the Play from choice"))
        assertEquals("Plex", ok.playChoice)
        assertEquals("Home ABS", ok.downloadChoice)
        assertTrue(ok.downloadPick.startsWith("Home ABS (the Download from choice"))
        val off = describeMerge(e, ctx.copy(onlineServerIds = emptySet(), deviceOnline = false), names)
        assertTrue(off.playPick.startsWith("asks (the chosen copy cannot play now"))
        assertTrue(off.downloadPick.startsWith("asks (the chosen copy is already downloaded"))
        assertFalse(off.deviceOnline)
    }

    @Test fun `a downloaded copy plays first`() {
        val e = entry(item("li_1", "abs", ServerSource.ABS), item("plex_2", "plex", ServerSource.PLEX, DownloadState.DOWNLOADED))
        val m = describeMerge(e, MergeContext(), names)
        assertEquals("Den Plex (a downloaded copy plays first)", m.playPick)
    }

    @Test fun `corrections are listed for the members only`() {
        val e = entry(item("li_1", "abs", ServerSource.ABS), item("plex_2", "plex", ServerSource.PLEX))
        val ctx = MergeContext(overrides = MergeOverrides(splits = setOf("li_1" to "li_9", "x" to "y"), merges = setOf("li_1" to "plex_2")))
        val m = describeMerge(e, ctx, names)
        assertEquals(listOf("li_1" to "li_9"), m.splits)
        assertEquals(listOf("li_1" to "plex_2"), m.merges)
        val text = buildReport(DiagnosticsInput("Same", "Same", emptyList(), m, now))
        assertTrue(text.contains("Group members: li_1, plex_2"))
        assertTrue(text.contains("Split corrections (not the same book): li_1 / li_9"))
        assertTrue(text.contains("Merge corrections (same book): li_1 / plex_2"))
    }

    @Test fun `the row shows only in debuggable builds`() {
        assertTrue(showDiagnosticsRow(true))
        assertFalse(showDiagnosticsRow(false))
    }
}
