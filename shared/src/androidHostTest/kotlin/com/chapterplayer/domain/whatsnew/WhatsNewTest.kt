package com.chapterplayer.domain.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhatsNewTest {
    private val changelog = """
        # Changelog

        Intro line that is not part of a version.

        ## 0.5.4 (18 November 2026)
        **New**
        - **Font choice:** Settings, Font.
        - Plain bullet.

        **Fixed**
        - A fix.

        ## 0.5.3 (earlier)
        Jellyfin as a third kind of server.
    """.trimIndent()

    @Test fun `reads each version with its date and lines`() {
        val notes = parseChangelog(changelog)
        assertEquals(listOf("0.5.4", "0.5.3"), notes.map { it.version })
        assertEquals("18 November 2026", notes[0].date)
        assertEquals("earlier", notes[1].date)
        assertEquals(
            listOf(
                NoteLine.Heading("New"),
                NoteLine.Bullet("**Font choice:** Settings, Font."),
                NoteLine.Bullet("Plain bullet."),
                NoteLine.Heading("Fixed"),
                NoteLine.Bullet("A fix."),
            ),
            notes[0].lines
        )
        assertEquals(listOf<NoteLine>(NoteLine.Plain("Jellyfin as a third kind of server.")), notes[1].lines)
    }

    @Test fun `a version without a date has none`() {
        val notes = parseChangelog("## 1.0.0\n- one")
        assertEquals("1.0.0", notes.single().version)
        assertNull(notes.single().date)
    }

    @Test fun `text before the first version and an empty changelog give nothing`() {
        assertEquals(emptyList(), parseChangelog("# Changelog\n\n- stray bullet"))
        assertEquals(emptyList(), parseChangelog(""))
    }

    @Test fun `bold marks split the text`() {
        assertEquals(
            listOf(TextRun("Font:", true), TextRun(" pick one", false)),
            splitBold("**Font:** pick one")
        )
        assertEquals(listOf(TextRun("a ", false), TextRun("b", true), TextRun(" c", false)), splitBold("a **b** c"))
        assertEquals(listOf(TextRun("unpaired ** mark", false)), splitBold("unpaired ** mark"))
        assertEquals(emptyList(), splitBold(""))
    }

    @Test fun `a debug build counts as its release`() {
        assertEquals("0.5.4", releaseVersion("0.5.4-debug.12"))
        assertEquals("0.5.4", releaseVersion("0.5.4"))
    }

    @Test fun `an update shows the notes of the new version once`() {
        val notes = parseChangelog(changelog)
        assertEquals("0.5.4", notesToShow(lastSeen = "0.5.3", current = "0.5.4", hasServers = true, notes = notes)?.version)
        assertNull(notesToShow(lastSeen = "0.5.4", current = "0.5.4", hasServers = true, notes = notes))
    }

    @Test fun `a fresh install sees no notes but an existing app without a record does`() {
        val notes = parseChangelog(changelog)
        assertNull(notesToShow(lastSeen = null, current = "0.5.4", hasServers = false, notes = notes))
        assertEquals("0.5.4", notesToShow(lastSeen = null, current = "0.5.4", hasServers = true, notes = notes)?.version)
    }

    @Test fun `a version the changelog does not know shows nothing`() {
        assertNull(notesToShow(lastSeen = "0.5.4", current = "0.6.0", hasServers = true, notes = parseChangelog(changelog)))
    }
}
