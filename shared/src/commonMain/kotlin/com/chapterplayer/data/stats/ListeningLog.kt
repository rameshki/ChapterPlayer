package com.chapterplayer.data.stats

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.stats.ServerListeningStats
import kotlinx.coroutines.flow.Flow

/** Listening recorded by this app on one local day ([day] is an epoch day) for one book. */
data class ListeningLogEntry(
    val day: Int,
    val bookId: String,
    val serverId: String,
    val source: ServerSource,
    val wallSeconds: Long,
    val bookSeconds: Long,
)

/** A day's total from a server's own history (Audiobookshelf's listening stats). */
data class ImportedDay(val serverId: String, val day: Int, val seconds: Long)

/** A book's all-time total from a server's own history, with the app's book id. */
data class ImportedItem(val serverId: String, val bookId: String, val seconds: Long)

/** The listening log and the imported server history the statistics page reads. */
interface ListeningLog {

    /** Adds each entry's seconds to the row of its day and book (a new row when there is none). */
    suspend fun add(entries: List<ListeningLogEntry>)

    fun observeLog(): Flow<List<ListeningLogEntry>>

    fun observeImportedDays(): Flow<List<ImportedDay>>

    fun observeImportedItems(): Flow<List<ImportedItem>>

    /** The servers whose history has been imported at least once. */
    suspend fun importedServerIds(): Set<String>

    /** Replaces everything imported from [serverId] with [stats] and records the import time. */
    suspend fun replaceImport(serverId: String, stats: ServerListeningStats, importedAtMs: Long)
}
