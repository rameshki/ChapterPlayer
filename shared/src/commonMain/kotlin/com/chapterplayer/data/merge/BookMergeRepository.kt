package com.chapterplayer.data.merge

import com.chapterplayer.data.local.dao.BookMergeDao
import com.chapterplayer.data.local.entity.BookMergeEntity
import com.chapterplayer.domain.browse.MergeOverrides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The user's corrections of the merged sources, kept across syncs and restarts. */
interface BookMergeRepository {
    val overrides: Flow<MergeOverrides>

    /** "Not the same book": [bookId] leaves the group it is in with [otherIds] and stays apart from each of them. */
    suspend fun split(bookId: String, otherIds: Collection<String>)

    /** "Merge with…": [bookId] and [otherId] are the same book from now on. */
    suspend fun merge(bookId: String, otherId: String)
}

internal const val KIND_SPLIT = "SPLIT"
internal const val KIND_MERGE = "MERGE"

/** The row for the pair [a] and [b] (ids in order); null for a book paired with itself. */
internal fun mergeRow(a: String, b: String, kind: String): BookMergeEntity? = when {
    a == b -> null
    a < b -> BookMergeEntity(a, b, kind)
    else -> BookMergeEntity(b, a, kind)
}

/** The overrides the stored [rows] describe; rows of an unknown kind are ignored. */
internal fun overridesOf(rows: List<BookMergeEntity>): MergeOverrides = MergeOverrides(
    splits = rows.filter { it.kind == KIND_SPLIT }.mapTo(LinkedHashSet()) { it.bookIdA to it.bookIdB },
    merges = rows.filter { it.kind == KIND_MERGE }.mapTo(LinkedHashSet()) { it.bookIdA to it.bookIdB },
)

/**
 * In the `book_merges` table (Room) beside the books, so the corrections are backed up and wiped with the rest of
 * the library data. A row for a book that has gone is harmless: the grouping ignores ids it does not see.
 */
class RoomBookMergeRepository(private val dao: BookMergeDao) : BookMergeRepository {

    override val overrides: Flow<MergeOverrides> = dao.observeAll().map(::overridesOf)

    override suspend fun split(bookId: String, otherIds: Collection<String>) {
        val rows = otherIds.distinct().mapNotNull { mergeRow(bookId, it, KIND_SPLIT) }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    override suspend fun merge(bookId: String, otherId: String) {
        mergeRow(bookId, otherId, KIND_MERGE)?.let { dao.upsertAll(listOf(it)) }
    }
}
