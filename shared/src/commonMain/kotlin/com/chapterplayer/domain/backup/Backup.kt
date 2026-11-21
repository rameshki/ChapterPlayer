package com.chapterplayer.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The name and version written at the top of every backup, so an import can tell what it was given. */
const val BACKUP_FORMAT = "chapterplayer-backup"
const val BACKUP_VERSION = 1

/**
 * A book as another install can find it: the server's address (without scheme, see [serverKey]) and the book's id on
 * that server, with the title and author as a fallback when the address changed. No tokens, ever.
 */
@Serializable
data class BackupBook(
    val server: String,
    val id: String,
    val title: String = "",
    val author: String = "",
)

@Serializable data class BackupFavourite(val book: BackupBook, val addedAt: Long = 0L)

/** One entry of "Up next"; the list is in queue order. */
@Serializable data class BackupQueued(val book: BackupBook, val addedAt: Long = 0L)

/** A correction of the "same book on several servers" matching: [kind] is "SPLIT" or "MERGE". */
@Serializable data class BackupCorrection(val a: BackupBook, val b: BackupBook, val kind: String)

/**
 * The simple settings that move between phones; a null field is left as it is on import. Settings that belong to the
 * phone (Wi-Fi only downloads, Android Auto resume) are not carried: a metered plan must not be flipped by a backup.
 */
@Serializable
data class BackupSettings(
    val theme: String? = null,
    val font: String? = null,
    val libraryLayout: String? = null,
    val playbackSpeed: Float? = null,
    val skipBackSeconds: Int? = null,
    val skipForwardSeconds: Int? = null,
    val mirrorProgress: Boolean? = null,
    val crossSyncOnAdd: Boolean? = null,
)

@Serializable
data class BackupFile(
    val format: String = BACKUP_FORMAT,
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long = 0L,
    val favourites: List<BackupFavourite> = emptyList(),
    val upNext: List<BackupQueued> = emptyList(),
    val corrections: List<BackupCorrection> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

private val BackupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

fun encodeBackup(file: BackupFile): String = BackupJson.encodeToString(BackupFile.serializer(), file)

/** What reading a backup text gave. */
sealed interface BackupParse {
    data class Ok(val file: BackupFile) : BackupParse
    /** Not a ChapterPlayer backup (or damaged). */
    data object NotABackup : BackupParse
    /** Written by a newer app than this one: it may hold things this version cannot place. */
    data class TooNew(val version: Int) : BackupParse
}

fun decodeBackup(text: String): BackupParse {
    val file = try {
        // The format name must be written in the text itself: every field has a default, so `{}` would else pass.
        val written = (BackupJson.parseToJsonElement(text.trim()) as? JsonObject)?.get("format")
        if ((written as? JsonPrimitive)?.contentOrNull != BACKUP_FORMAT) return BackupParse.NotABackup
        BackupJson.decodeFromString(BackupFile.serializer(), text.trim())
    } catch (_: Exception) {
        return BackupParse.NotABackup
    }
    if (file.version > BACKUP_VERSION) return BackupParse.TooNew(file.version)
    return BackupParse.Ok(file)
}

/** A server address without its scheme, case or trailing slashes, so http://Host:1/ and https://host:1 are one server. */
fun serverKey(baseUrl: String): String =
    baseUrl.trim().lowercase().substringAfter("://").trimEnd('/')

/** One book this install holds, for finding what a backup names. */
data class IndexedBook(val id: String, val serverKey: String, val title: String, val author: String)

/** Finds the local id of a book named in a backup: the same server and id first, else the one book with that title and author. */
class BookIndex(books: List<IndexedBook>) {
    private val byServerAndId = books.associateBy { it.serverKey to it.id }
    private val byTitleAndAuthor = books.groupBy { titleKey(it.title, it.author) }

    /** The local id for [ref]; [byTitle] false asks for an exact server and id match only. */
    fun resolve(ref: BackupBook, byTitle: Boolean = true): String? {
        byServerAndId[ref.server to ref.id]?.let { return it.id }
        if (!byTitle || ref.title.isBlank()) return null
        return byTitleAndAuthor[titleKey(ref.title, ref.author)]?.singleOrNull()?.id
    }

    private fun titleKey(title: String, author: String) = title.trim().lowercase() + "\u0000" + author.trim().lowercase()
}

/** What an import will write, in local book ids; [skipped] counts the entries whose book this install does not have. */
data class RestorePlan(
    val favourites: List<Pair<String, Long>>,
    val upNext: List<String>,
    /** (idA, idB, kind) with idA below idB, as the table stores them. */
    val corrections: List<Triple<String, String, String>>,
    val skipped: Int,
)

/**
 * Turns a backup into writes. Favourites already here are left alone (their first date stays), the queue gets the
 * books it does not hold yet after the books already queued, and corrections replace the same pair's older one.
 */
fun restorePlan(file: BackupFile, index: BookIndex, haveFavourites: Set<String>, haveQueue: List<String>): RestorePlan {
    var skipped = 0
    val favourites = mutableListOf<Pair<String, Long>>()
    for (fav in file.favourites) {
        val id = index.resolve(fav.book)
        when {
            id == null -> skipped++
            id !in haveFavourites && favourites.none { it.first == id } -> favourites += id to fav.addedAt
        }
    }
    val queue = mutableListOf<String>()
    for (queued in file.upNext) {
        val id = index.resolve(queued.book)
        when {
            id == null -> skipped++
            id !in haveQueue && id !in queue -> queue += id
        }
    }
    val corrections = mutableListOf<Triple<String, String, String>>()
    for (c in file.corrections) {
        // A correction names a pair of copies: a guess by title could bind it to the wrong copies, so only exact matches count.
        val a = index.resolve(c.a, byTitle = false)
        val b = index.resolve(c.b, byTitle = false)
        if (a == null || b == null || a == b || c.kind !in CORRECTION_KINDS) { skipped++; continue }
        val (low, high) = if (a < b) a to b else b to a
        corrections.removeAll { it.first == low && it.second == high }
        corrections += Triple(low, high, c.kind)
    }
    return RestorePlan(favourites, queue, corrections, skipped)
}

private val CORRECTION_KINDS = setOf("SPLIT", "MERGE")

/** The line shown after an import. */
fun restoreSummary(plan: RestorePlan, settingsApplied: Int): String {
    val parts = mutableListOf<String>()
    if (plan.favourites.isNotEmpty()) parts += "${plan.favourites.size} favourite" + if (plan.favourites.size == 1) "" else "s"
    if (plan.upNext.isNotEmpty()) parts += "${plan.upNext.size} in Up next"
    if (plan.corrections.isNotEmpty()) parts += "${plan.corrections.size} correction" + if (plan.corrections.size == 1) "" else "s"
    if (settingsApplied > 0) parts += "$settingsApplied setting" + if (settingsApplied == 1) "" else "s"
    val done = if (parts.isEmpty()) "Nothing new to restore." else "Restored " + parts.joinToString(", ") + "."
    return if (plan.skipped > 0) "$done ${plan.skipped} skipped: ${if (plan.skipped == 1) "that book is" else "those books are"} not in this app yet (add the server, let it sync, then restore again)." else done
}
