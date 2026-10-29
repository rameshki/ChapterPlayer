package com.chapterplayer.testing

import com.chapterplayer.data.stats.ImportedDay
import com.chapterplayer.data.stats.ImportedItem
import com.chapterplayer.data.stats.ListeningLog
import com.chapterplayer.data.stats.ListeningLogEntry
import com.chapterplayer.domain.stats.ServerListeningStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A [ListeningLog] in memory. [onAdd] runs before each add (throw to simulate a failing database). */
class FakeListeningLog : ListeningLog {
    val log = MutableStateFlow<List<ListeningLogEntry>>(emptyList())
    val days = MutableStateFlow<List<ImportedDay>>(emptyList())
    val items = MutableStateFlow<List<ImportedItem>>(emptyList())
    val imports = mutableMapOf<String, Long>()
    var onAdd: () -> Unit = {}
    var addCalls = 0

    override suspend fun add(entries: List<ListeningLogEntry>) {
        addCalls++
        onAdd()
        val rows = log.value.associateBy { it.day to it.bookId }.toMutableMap()
        for (e in entries) {
            val old = rows[e.day to e.bookId]
            rows[e.day to e.bookId] = old?.copy(wallSeconds = old.wallSeconds + e.wallSeconds, bookSeconds = old.bookSeconds + e.bookSeconds) ?: e
        }
        log.value = rows.values.sortedWith(compareBy({ it.day }, { it.bookId }))
    }

    override fun observeLog(): Flow<List<ListeningLogEntry>> = log
    override fun observeImportedDays(): Flow<List<ImportedDay>> = days
    override fun observeImportedItems(): Flow<List<ImportedItem>> = items
    override suspend fun importedServerIds(): Set<String> = imports.keys.toSet()

    override suspend fun replaceImport(serverId: String, stats: ServerListeningStats, importedAtMs: Long) {
        days.value = days.value.filter { it.serverId != serverId } + stats.days.map { ImportedDay(serverId, it.key, it.value) }
        val logged = log.value.groupBy { it.bookId }.mapValues { (_, rows) -> rows.sumOf { it.wallSeconds } }
        items.value = items.value.filter { it.serverId != serverId } +
            stats.items.map { ImportedItem(serverId, it.key, it.value, logged[it.key] ?: 0L) }
        imports[serverId] = importedAtMs
    }

    /** Wall seconds per (day, book). */
    fun wall(): Map<Pair<Int, String>, Long> = log.value.associate { (it.day to it.bookId) to it.wallSeconds }
}
