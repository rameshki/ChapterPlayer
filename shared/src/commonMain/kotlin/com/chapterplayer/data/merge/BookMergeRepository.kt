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

    /**
     * "Merge with…": [bookId] and [otherId] are the same book from now on. [group] and [otherGroup] are the copies
     * each is already shown with: every "Not the same book" between the two groups is forgotten, so the join can happen.
     */
    suspend fun merge(bookId: String, otherId: String, group: Collection<String> = listOf(bookId), otherGroup: Collection<String> = listOf(otherId))

    /** Forgets every correction about [bookIds] (their server was removed). */
    suspend fun forgetBooks(bookIds: Collection<String>)
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
 * In the `book_merges` table (Room) beside the books. Removing a server forgets its books' rows (see [forgetBooks]);
 * a row for a book that is only gone for now (a library turned off, a sync that missed it) stays, and the grouping
 * ignores ids it does not see.
 */
class RoomBookMergeRepository(private val dao: BookMergeDao) : BookMergeRepository {

    override val overrides: Flow<MergeOverrides> = dao.observeAll().map(::overridesOf)

    override suspend fun split(bookId: String, otherIds: Collection<String>) {
        val rows = otherIds.distinct().mapNotNull { mergeRow(bookId, it, KIND_SPLIT) }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    override suspend fun merge(bookId: String, otherId: String, group: Collection<String>, otherGroup: Collection<String>) {
        val row = mergeRow(bookId, otherId, KIND_MERGE) ?: return
        for (a in (group + bookId).distinct()) for (b in (otherGroup + otherId).distinct()) {
            val split = mergeRow(a, b, KIND_SPLIT) ?: continue
            dao.deleteKind(split.bookIdA, split.bookIdB, KIND_SPLIT)
        }
        dao.upsertAll(listOf(row))
    }

    override suspend fun forgetBooks(bookIds: Collection<String>) {
        for (chunk in bookIds.distinct().chunked(FORGET_CHUNK)) dao.deleteForBooks(chunk)
    }

    private companion object {
        // SQLite allows at most 999 bound arguments; each id is bound twice.
        const val FORGET_CHUNK = 400
    }
}
