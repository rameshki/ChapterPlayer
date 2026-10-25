package com.chapterplayer.data.stats

import com.chapterplayer.data.local.dao.ListeningDao
import com.chapterplayer.data.local.entity.ImportedListeningDayEntity
import com.chapterplayer.data.local.entity.ImportedListeningItemEntity
import com.chapterplayer.data.local.entity.ListeningImportEntity
import com.chapterplayer.data.local.entity.ListeningLogEntity
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.stats.ServerListeningStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomListeningLogTest {

    /** The DAO's SQL, in memory. */
    private class MemoryDao : ListeningDao {
        val log = MutableStateFlow<List<ListeningLogEntity>>(emptyList())
        val days = MutableStateFlow<List<ImportedListeningDayEntity>>(emptyList())
        val items = MutableStateFlow<List<ImportedListeningItemEntity>>(emptyList())
        val imports = mutableListOf<ListeningImportEntity>()

        override fun observeLog(): Flow<List<ListeningLogEntity>> = log
        override suspend fun addTo(day: Int, bookId: String, wallSeconds: Long, bookSeconds: Long): Int {
            var changed = 0
            log.value = log.value.map {
                if (it.day == day && it.bookId == bookId) { changed++; it.copy(wallSeconds = it.wallSeconds + wallSeconds, bookSeconds = it.bookSeconds + bookSeconds) } else it
            }
            return changed
        }
        override suspend fun insertLog(row: ListeningLogEntity): Long {
            if (log.value.any { it.day == row.day && it.bookId == row.bookId }) return -1
            log.value = log.value + row
            return log.value.size.toLong()
        }
        override fun observeImportedDays(): Flow<List<ImportedListeningDayEntity>> = days
        override fun observeImportedItems(): Flow<List<ImportedListeningItemEntity>> = items
        override suspend fun importedServerIds(): List<String> = imports.map { it.serverId }
        override suspend fun deleteImportedDays(serverId: String) { days.value = days.value.filter { it.serverId != serverId } }
        override suspend fun deleteImportedItems(serverId: String) { items.value = items.value.filter { it.serverId != serverId } }
        override suspend fun insertImportedDays(rows: List<ImportedListeningDayEntity>) { days.value = days.value + rows }
        override suspend fun insertImportedItems(rows: List<ImportedListeningItemEntity>) { items.value = items.value + rows }
        override suspend fun upsertImport(row: ListeningImportEntity) { imports.removeAll { it.serverId == row.serverId }; imports += row }
    }

    @Test
    fun `adding to a day and book sums the seconds in one row`() = runTest {
        val dao = MemoryDao()
        val log = RoomListeningLog(dao)
        log.add(listOf(ListeningLogEntry(5, "abs_a", "s1", ServerSource.ABS, 30, 45)))
        log.add(listOf(ListeningLogEntry(5, "abs_a", "s1", ServerSource.ABS, 10, 15), ListeningLogEntry(6, "plex_b", "p1", ServerSource.PLEX, 3, 3)))

        assertEquals(
            listOf(
                ListeningLogEntry(5, "abs_a", "s1", ServerSource.ABS, 40, 60),
                ListeningLogEntry(6, "plex_b", "p1", ServerSource.PLEX, 3, 3),
            ),
            log.observeLog().first()
        )
    }

    @Test
    fun `an import replaces the earlier import of that server only`() = runTest {
        val dao = MemoryDao()
        val log = RoomListeningLog(dao)
        log.replaceImport("s1", ServerListeningStats(mapOf(1 to 100L, 2 to 200L), mapOf("abs_a" to 300L)), 1_000)
        log.replaceImport("s2", ServerListeningStats(mapOf(1 to 7L)), 1_000)
        log.replaceImport("s1", ServerListeningStats(mapOf(3 to 50L)), 2_000)

        assertEquals(setOf(ImportedDay("s2", 1, 7), ImportedDay("s1", 3, 50)), log.observeImportedDays().first().toSet())
        assertEquals(emptyList(), log.observeImportedItems().first())
        assertEquals(setOf("s1", "s2"), log.importedServerIds())
        assertEquals(2_000L, dao.imports.single { it.serverId == "s1" }.importedAt)
    }
}
