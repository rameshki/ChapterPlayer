package com.chapterplayer.domain.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BackupTest {
    private val index = BookIndex(
        listOf(
            IndexedBook("b1", "abs.example:13378", "The First Book", "A. Writer"),
            IndexedBook("b2", "abs.example:13378", "The Second Book", "A. Writer"),
            IndexedBook("j9", "jf.example", "The First Book", "A. Writer"),
            IndexedBook("t1", "abs.example:13378", "Twin", "Same Person"),
            IndexedBook("t2", "abs.example:13378", "Twin", "Same Person"),
        )
    )

    private fun ref(server: String, id: String, title: String = "", author: String = "") = BackupBook(server, id, title, author)

    @Test fun `a book is found by server and id first`() {
        assertEquals("b2", index.resolve(ref("abs.example:13378", "b2")))
    }

    @Test fun `a changed server address falls back to the one book with that title and author`() {
        assertEquals("b2", index.resolve(ref("old.example", "zzz", "the second book ", "A. WRITER")))
    }

    @Test fun `a title shared by two books is not guessed and a missing book gives null`() {
        assertNull(index.resolve(ref("old.example", "zzz", "Twin", "Same Person")))
        assertNull(index.resolve(ref("old.example", "zzz", "Nothing Like It", "Nobody")))
        assertNull(index.resolve(ref("old.example", "zzz")))
    }

    @Test fun `a correction needs an exact match and is skipped when only the title matches`() {
        val file = BackupFile(
            corrections = listOf(BackupCorrection(ref("old.example", "zzz", "The Second Book", "A. Writer"), ref("abs.example:13378", "b1"), "SPLIT")),
        )
        val plan = restorePlan(file, index, emptySet(), emptyList())
        assertEquals(emptyList(), plan.corrections)
        assertEquals(1, plan.skipped)
    }

    @Test fun `server keys ignore scheme case and trailing slashes`() {
        assertEquals("host.example:1", serverKey("HTTPS://Host.Example:1/"))
        assertEquals(serverKey("http://host.example:1"), serverKey("https://host.example:1//"))
    }

    @Test fun `a backup survives writing and reading`() {
        val file = BackupFile(
            exportedAt = 5L,
            favourites = listOf(BackupFavourite(ref("s", "b1", "T", "A"), 7L)),
            upNext = listOf(BackupQueued(ref("s", "b2"))),
            corrections = listOf(BackupCorrection(ref("s", "b1"), ref("s", "b2"), "SPLIT")),
            settings = BackupSettings(theme = "DARK", skipBackSeconds = 30, mirrorProgress = false),
        )
        assertEquals(BackupParse.Ok(file), decodeBackup(encodeBackup(file)))
    }

    @Test fun `text that is not a backup is refused`() {
        assertEquals(BackupParse.NotABackup, decodeBackup(""))
        assertEquals(BackupParse.NotABackup, decodeBackup("hello"))
        assertEquals(BackupParse.NotABackup, decodeBackup("{}"))
        assertEquals(BackupParse.NotABackup, decodeBackup("{\"favourites\":[]}"))
        assertEquals(BackupParse.NotABackup, decodeBackup("[1,2]"))
        assertEquals(BackupParse.NotABackup, decodeBackup("{\"format\":\"something-else\"}"))
        assertEquals(BackupParse.NotABackup, decodeBackup("{\"favourites\":[{\"oops\":1}]}"))
    }

    @Test fun `a backup from a newer app is named as too new`() {
        val text = "{\"format\":\"$BACKUP_FORMAT\",\"version\":${BACKUP_VERSION + 1}}"
        assertEquals(BackupParse.TooNew(BACKUP_VERSION + 1), decodeBackup(text))
    }

    @Test fun `unknown fields from a later minor change are ignored`() {
        val text = "{\"format\":\"$BACKUP_FORMAT\",\"version\":1,\"extra\":true,\"settings\":{\"theme\":\"LIGHT\",\"future\":1}}"
        assertEquals("LIGHT", assertIs<BackupParse.Ok>(decodeBackup(text)).file.settings.theme)
    }

    @Test fun `the plan keeps what is new and counts what cannot be placed`() {
        val file = BackupFile(
            favourites = listOf(
                BackupFavourite(ref("abs.example:13378", "b1"), 10L),
                BackupFavourite(ref("abs.example:13378", "b2"), 11L),
                BackupFavourite(ref("gone.example", "x", "Unknown", "Nobody"), 12L),
            ),
            upNext = listOf(
                BackupQueued(ref("abs.example:13378", "b2")),
                BackupQueued(ref("abs.example:13378", "b1")),
                BackupQueued(ref("abs.example:13378", "b2")),
            ),
        )
        val plan = restorePlan(file, index, haveFavourites = setOf("b1"), haveQueue = listOf("b1"))
        assertEquals(listOf("b2" to 11L), plan.favourites)
        assertEquals(listOf("b2"), plan.upNext)
        assertEquals(1, plan.skipped)
    }

    @Test fun `corrections are stored with the lower id first and the last one for a pair wins`() {
        val file = BackupFile(
            corrections = listOf(
                BackupCorrection(ref("abs.example:13378", "b2"), ref("abs.example:13378", "b1"), "SPLIT"),
                BackupCorrection(ref("abs.example:13378", "b1"), ref("abs.example:13378", "b2"), "MERGE"),
                BackupCorrection(ref("abs.example:13378", "b1"), ref("abs.example:13378", "b1"), "MERGE"),
                BackupCorrection(ref("abs.example:13378", "b1"), ref("jf.example", "j9"), "WEIRD"),
            ),
        )
        val plan = restorePlan(file, index, emptySet(), emptyList())
        assertEquals(listOf(Triple("b1", "b2", "MERGE")), plan.corrections)
        assertEquals(2, plan.skipped)
    }

    @Test fun `the summary says what was restored and what was skipped`() {
        val plan = RestorePlan(listOf("a" to 1L, "b" to 2L), listOf("a"), emptyList(), skipped = 2)
        assertEquals(
            "Restored 2 favourites, 1 in Up next, 3 settings. 2 skipped: those books are not in this app yet (add the server, let it sync, then restore again).",
            restoreSummary(plan, settingsApplied = 3)
        )
        assertEquals("Nothing new to restore.", restoreSummary(RestorePlan(emptyList(), emptyList(), emptyList(), 0), 0))
    }
}
