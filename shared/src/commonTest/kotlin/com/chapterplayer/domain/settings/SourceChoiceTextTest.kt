package com.chapterplayer.domain.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.SourceChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceChoiceTextTest {

    private fun abs(id: String, name: String) = ServerConfig(id, ServerSource.ABS, name, "http://$id", "t")
    private fun plex(id: String, name: String) = ServerConfig(id, ServerSource.PLEX, name, "http://$id", "t")

    private val onePerType = listOf(abs("a1", "Home ABS"), plex("p1", "Home Plex"))
    private val twoAbs = listOf(abs("a1", "Home ABS"), abs("a2", "Work ABS"), plex("p1", "Home Plex"))

    @Test
    fun `the lines are ask - Audiobookshelf - Plex with one server of each`() {
        val card = sourceChoiceCard(SourceChoice.Ask, onePerType, SourcePurpose.DOWNLOAD)
        assertEquals(listOf("Ask every time", "Audiobookshelf", "Plex"), card.lines.map { it.label })
        assertTrue(card.lines.none { it.indented })
        assertEquals(listOf(true, false, false), card.lines.map { it.selected })
        assertTrue(card.lines.all { it.detail.isNotBlank() })
    }

    @Test
    fun `two servers of a type add an indented line per server under that type`() {
        val card = sourceChoiceCard(SourceChoice.OfServer("a2"), twoAbs, SourcePurpose.PLAY)
        assertEquals(
            listOf("Ask every time", "Audiobookshelf", "Audiobookshelf: Home ABS", "Audiobookshelf: Work ABS", "Plex"),
            card.lines.map { it.label },
        )
        assertEquals(listOf(false, false, true, true, false), card.lines.map { it.indented })
        assertEquals(SourceChoice.OfServer("a2"), card.lines.single { it.selected }.choice)
        assertEquals("Audiobookshelf: Work ABS", card.label)
        assertNull(card.removedNote)
    }

    @Test
    fun `the play card says a downloaded copy always plays first`() {
        val play = sourceChoiceCard(SourceChoice.Ask, onePerType, SourcePurpose.PLAY)
        assertEquals("Play from (when not downloaded)", play.title)
        assertEquals("A downloaded copy always plays first.", play.note)
        val download = sourceChoiceCard(SourceChoice.Ask, onePerType, SourcePurpose.DOWNLOAD)
        assertEquals("Download from", download.title)
        assertNull(download.note)
    }

    @Test
    fun `a removed server's choice shows as ask and says so`() {
        val card = sourceChoiceCard(SourceChoice.OfServer("gone"), onePerType, SourcePurpose.DOWNLOAD)
        assertEquals("Ask every time", card.label)
        assertEquals("The server chosen before was removed, so it asks.", card.removedNote)
        assertEquals(SourceChoice.Ask, card.lines.single { it.selected }.choice)
    }

    @Test
    fun `a chosen server stays listed when it is the last of its type`() {
        val card = sourceChoiceCard(SourceChoice.OfServer("a1"), onePerType, SourcePurpose.DOWNLOAD)
        assertEquals(listOf("Ask every time", "Audiobookshelf", "Audiobookshelf: Home ABS", "Plex"), card.lines.map { it.label })
        assertTrue(card.lines.single { it.selected }.indented)
    }

    @Test
    fun `each line explains itself`() {
        val download = sourceChoiceCard(SourceChoice.Ask, twoAbs, SourcePurpose.DOWNLOAD).lines.associate { it.label to it.detail }
        assertEquals("Download asks which copy to fetch.", download["Ask every time"])
        assertEquals("Downloads from Audiobookshelf without asking; asks when it cannot.", download["Audiobookshelf"])
        assertEquals("Downloads from Work ABS without asking; asks when it cannot.", download["Audiobookshelf: Work ABS"])
        val play = sourceChoiceCard(SourceChoice.Ask, twoAbs, SourcePurpose.PLAY).lines.associate { it.label to it.detail }
        assertEquals("Play asks which copy to stream.", play["Ask every time"])
        assertEquals("Streams from Plex without asking; asks when it cannot play.", play["Plex"])
    }

    @Test
    fun `a type without servers is not offered unless it is the saved choice`() {
        val twoPlexOnly = listOf(plex("p1", "Home Plex"), plex("p2", "Work Plex"))
        val card = sourceChoiceCard(SourceChoice.Ask, twoPlexOnly, SourcePurpose.PLAY)
        assertEquals(listOf("Ask every time", "Plex", "Plex: Home Plex", "Plex: Work Plex"), card.lines.map { it.label })
        val saved = sourceChoiceCard(SourceChoice.OfType(ServerSource.ABS), twoPlexOnly, SourcePurpose.PLAY)
        assertEquals("Audiobookshelf", saved.lines.single { it.selected }.label)
    }

    @Test
    fun `servers with the same or no name are told apart`() {
        val same = listOf(
            ServerConfig("a1", ServerSource.ABS, "Home", "https://one.example", "t"),
            ServerConfig("a2", ServerSource.ABS, "Home", "https://two.example", "t"),
            ServerConfig("a3", ServerSource.ABS, "", "https://three.example", "t"),
        )
        val labels = sourceChoiceCard(SourceChoice.Ask, same, SourcePurpose.DOWNLOAD).lines.map { it.label }
        assertEquals(
            listOf("Ask every time", "Audiobookshelf", "Audiobookshelf: Home (one.example)", "Audiobookshelf: Home (two.example)", "Audiobookshelf: Audiobookshelf"),
            labels,
        )
    }

    @Test
    fun `the choices apply only with two or more servers`() {
        assertTrue(sourceChoicesApply(onePerType))
        assertFalse(sourceChoicesApply(listOf(abs("a1", "Home ABS"))))
        assertFalse(sourceChoicesApply(emptyList()))
    }

    @Test
    fun `the hub summary is one short phrase - and nothing when both ask`() {
        assertNull(sourceChoicesSummary(SourceChoice.Ask, SourceChoice.Ask, onePerType))
        assertEquals("Download and play from Plex", sourceChoicesSummary(SourceChoice.OfType(ServerSource.PLEX), SourceChoice.OfType(ServerSource.PLEX), onePerType))
        assertEquals(
            "Download from Work ABS, play from Plex",
            sourceChoicesSummary(SourceChoice.OfServer("a2"), SourceChoice.OfType(ServerSource.PLEX), twoAbs),
        )
        assertEquals("Download from ABS, play asks", sourceChoicesSummary(SourceChoice.OfType(ServerSource.ABS), SourceChoice.OfServer("gone"), twoAbs))
        assertEquals("Play from Plex, download asks", sourceChoicesSummary(SourceChoice.Ask, SourceChoice.OfType(ServerSource.PLEX), twoAbs))
        assertEquals("Play from Plex", sourceChoicesSummary(SourceChoice.OfType(ServerSource.ABS), SourceChoice.OfType(ServerSource.PLEX), twoAbs, downloads = false))
        assertNull(sourceChoicesSummary(SourceChoice.OfType(ServerSource.ABS), SourceChoice.Ask, twoAbs, downloads = false))
        assertNull(sourceChoicesSummary(SourceChoice.OfType(ServerSource.ABS), SourceChoice.OfType(ServerSource.ABS), listOf(abs("a1", "Home ABS"))))
        for (summary in listOf(sourceChoicesSummary(SourceChoice.OfServer("a2"), SourceChoice.OfType(ServerSource.PLEX), twoAbs)!!)) {
            assertFalse(summary.contains(" · "))
        }
    }

    @Test
    fun `the downloads summary carries the choices`() {
        assertEquals(
            "Wi-Fi only · Internal storage · No limit · Download and play from Plex",
            downloadsSummary(true, null, null, choices = "Download and play from Plex"),
        )
        assertEquals("Not available on iOS yet", downloadsSummary(true, null, null, available = false, choices = "Play from Plex"))
        assertFalse(downloadsSummary(true, null, null).contains("Play"))
    }
}
