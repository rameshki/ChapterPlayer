package com.chapterplayer.data.stats

import com.chapterplayer.data.local.dao.ListeningDao
import com.chapterplayer.data.local.entity.ImportedListeningDayEntity
import com.chapterplayer.data.local.entity.ImportedListeningItemEntity
import com.chapterplayer.data.local.entity.ListeningImportEntity
import com.chapterplayer.data.local.entity.ListeningLogEntity
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.stats.ServerListeningStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The [ListeningLog] in Room. [inTransaction] runs a block as one write transaction (the app passes Room's). */
class RoomListeningLog(
    private val dao: ListeningDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) : ListeningLog {

    override suspend fun add(entries: List<ListeningLogEntry>) {
        if (entries.isEmpty()) return
        inTransaction {
            for (e in entries) {
                if (dao.addTo(e.day, e.bookId, e.wallSeconds, e.bookSeconds) > 0) continue
                val inserted = dao.insertLog(ListeningLogEntity(e.day, e.bookId, e.serverId, e.source.name, e.wallSeconds, e.bookSeconds))
                // Another writer made the row in between: add to it after all.
                if (inserted == -1L) dao.addTo(e.day, e.bookId, e.wallSeconds, e.bookSeconds)
            }
        }
    }

    override fun observeLog(): Flow<List<ListeningLogEntry>> = dao.observeLog().map { rows ->
        rows.map { ListeningLogEntry(it.day, it.bookId, it.serverId, sourceOf(it.source), it.wallSeconds, it.bookSeconds) }
    }

    override fun observeImportedDays(): Flow<List<ImportedDay>> = dao.observeImportedDays().map { rows ->
        rows.map { ImportedDay(it.serverId, it.day, it.seconds) }
    }

    override fun observeImportedItems(): Flow<List<ImportedItem>> = dao.observeImportedItems().map { rows ->
        rows.map { ImportedItem(it.serverId, it.bookId, it.seconds, it.loggedAtImport) }
    }

    override suspend fun importedServerIds(): Set<String> = dao.importedServerIds().toSet()

    override suspend fun replaceImport(serverId: String, stats: ServerListeningStats, importedAtMs: Long) {
        inTransaction {
            val logged = dao.loggedTotals().associate { it.bookId to it.seconds }
            dao.deleteImportedDays(serverId)
            dao.deleteImportedItems(serverId)
            dao.insertImportedDays(stats.days.map { (day, seconds) -> ImportedListeningDayEntity(serverId, day, seconds) })
            dao.insertImportedItems(stats.items.map { (bookId, seconds) -> ImportedListeningItemEntity(serverId, bookId, seconds, logged[bookId] ?: 0L) })
            dao.upsertImport(ListeningImportEntity(serverId, importedAtMs))
        }
    }

    private fun sourceOf(name: String): ServerSource = ServerSource.entries.firstOrNull { it.name == name } ?: ServerSource.ABS
}
