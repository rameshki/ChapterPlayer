package com.chapterplayer.data.backup

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.BookMergeDao
import com.chapterplayer.data.local.dao.FavouriteDao
import com.chapterplayer.data.local.dao.UpNextDao
import com.chapterplayer.data.local.entity.BookMergeEntity
import com.chapterplayer.data.local.entity.FavouriteEntity
import com.chapterplayer.data.local.entity.UpNextEntity
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppFont
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.backup.BackupBook
import com.chapterplayer.domain.backup.BackupCorrection
import com.chapterplayer.domain.backup.BackupFavourite
import com.chapterplayer.domain.backup.BackupFile
import com.chapterplayer.domain.backup.BackupParse
import com.chapterplayer.domain.backup.BackupQueued
import com.chapterplayer.domain.backup.BackupSettings
import com.chapterplayer.domain.backup.BookIndex
import com.chapterplayer.domain.backup.IndexedBook
import com.chapterplayer.domain.backup.decodeBackup
import com.chapterplayer.domain.backup.encodeBackup
import com.chapterplayer.domain.backup.restorePlan
import com.chapterplayer.domain.backup.restoreSummary
import com.chapterplayer.domain.backup.serverKey
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/** What an import did, in words for the screen. */
sealed interface RestoreResult {
    data class Done(val summary: String) : RestoreResult
    data class Refused(val reason: String) : RestoreResult
}

/**
 * Export and import of what lives only on this phone (review R27): favourites, Up next, the corrections of merged
 * books and the simple settings, as one text (JSON, versioned) the person keeps or moves wherever they like. Books are
 * named by server address and id (with title and author as a fallback), never by this phone's own ids; no tokens, and
 * no passwords, are ever in it. Statistics and the position of Plex-only books are not in version 1.
 */
class BackupService(
    private val books: BookDao,
    private val servers: ServerRepository,
    private val favourites: FavouriteDao,
    private val upNext: UpNextDao,
    private val merges: BookMergeDao,
    private val settings: AppSettings,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** The backup text for what this phone holds now. */
    suspend fun export(): String {
        val refs = refs()
        val favs = favourites.getAll().mapNotNull { row -> refs[row.bookId]?.let { BackupFavourite(it, row.addedAt) } }
        val queue = upNext.getAll().mapNotNull { row -> refs[row.bookId]?.let { BackupQueued(it, row.addedAt) } }
        val corrections = merges.getAll().mapNotNull { row ->
            val a = refs[row.bookIdA]
            val b = refs[row.bookIdB]
            if (a == null || b == null) null else BackupCorrection(a, b, row.kind)
        }
        return encodeBackup(
            BackupFile(
                exportedAt = now(),
                favourites = favs,
                upNext = queue,
                corrections = corrections,
                settings = readSettings(),
            )
        )
    }

    /** Puts a backup text back: new favourites and queue entries are added, corrections and settings are set. */
    suspend fun restore(text: String): RestoreResult {
        val file = when (val parsed = decodeBackup(text)) {
            is BackupParse.Ok -> parsed.file
            BackupParse.NotABackup -> return RestoreResult.Refused("That is not a ChapterPlayer backup.")
            is BackupParse.TooNew -> return RestoreResult.Refused("This backup was made by a newer ChapterPlayer. Update the app first.")
        }
        val local = books.observeAll().first()
        val keys = serverKeys()
        val index = BookIndex(local.map { IndexedBook(it.id, keys[it.serverId].orEmpty(), it.title, it.author) })
        val plan = restorePlan(
            file, index,
            haveFavourites = favourites.getAll().mapTo(mutableSetOf()) { it.bookId },
            haveQueue = upNext.getAll().map { it.bookId },
        )
        if (plan.favourites.isNotEmpty()) favourites.insertAll(plan.favourites.map { (id, at) -> FavouriteEntity(id, if (at > 0L) at else now()) })
        if (plan.upNext.isNotEmpty()) {
            val start = (upNext.getAll().maxOfOrNull { it.position } ?: -1) + 1
            val added = now()
            upNext.insertAll(plan.upNext.mapIndexed { i, id -> UpNextEntity(id, start + i, added) })
        }
        if (plan.corrections.isNotEmpty()) merges.upsertAll(plan.corrections.map { (a, b, kind) -> BookMergeEntity(a, b, kind) })
        val applied = applySettings(file.settings)
        return RestoreResult.Done(restoreSummary(plan, applied))
    }

    // Each local book id as a reference another install can find: the server's address, the id, the title and author.
    private suspend fun refs(): Map<String, BackupBook> {
        val keys = serverKeys()
        return books.observeAll().first()
            .mapNotNull { e -> keys[e.serverId]?.let { e.id to BackupBook(it, e.id, e.title, e.author) } }
            .toMap()
    }

    private suspend fun serverKeys(): Map<String, String> = servers.observeAll().first().associate { it.id to serverKey(it.baseUrl) }

    private suspend fun readSettings() = BackupSettings(
        theme = settings.themeMode.first().name,
        font = settings.appFont.first().name,
        libraryLayout = settings.libraryLayout.first().name,
        resumeOnCarConnect = settings.resumeOnCarConnect.first(),
        downloadWifiOnly = settings.downloadWifiOnly.first(),
        playbackSpeed = settings.playbackSpeed.first(),
        skipBackSeconds = settings.skipBackSeconds.first(),
        skipForwardSeconds = settings.skipForwardSeconds.first(),
        mirrorProgress = settings.mirrorProgress.first(),
        crossSyncOnAdd = settings.crossSyncOnAdd.first(),
    )

    /** Sets every setting the backup holds that this app understands; returns how many were set. */
    private suspend fun applySettings(s: BackupSettings): Int {
        var n = 0
        s.theme?.let { name -> ThemeMode.entries.firstOrNull { it.name == name }?.let { settings.setThemeMode(it); n++ } }
        s.font?.let { name -> AppFont.entries.firstOrNull { it.name == name }?.let { settings.setAppFont(it); n++ } }
        s.libraryLayout?.let { name -> LibraryLayout.entries.firstOrNull { it.name == name }?.let { settings.setLibraryLayout(it); n++ } }
        s.resumeOnCarConnect?.let { settings.setResumeOnCarConnect(it); n++ }
        s.downloadWifiOnly?.let { settings.setDownloadWifiOnly(it); n++ }
        s.playbackSpeed?.let { settings.setPlaybackSpeed(it); n++ }
        s.skipBackSeconds?.let { settings.setSkipBackSeconds(it); n++ }
        s.skipForwardSeconds?.let { settings.setSkipForwardSeconds(it); n++ }
        s.mirrorProgress?.let { settings.setMirrorProgress(it); n++ }
        s.crossSyncOnAdd?.let { settings.setCrossSyncOnAdd(it); n++ }
        return n
    }
}
