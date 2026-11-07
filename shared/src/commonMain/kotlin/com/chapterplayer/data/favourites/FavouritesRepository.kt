package com.chapterplayer.data.favourites

import com.chapterplayer.data.local.dao.FavouriteDao
import com.chapterplayer.data.local.entity.FavouriteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * The person's favourite books, kept on this phone only (Audiobookshelf has no favourites to sync with, so they do not
 * follow the person to another device). Rows are per book id; a book several servers have is one entry, which counts
 * as a favourite when any of its copies has a row (`isFavourite`), and the screens pass every copy's id
 * (`favouriteRowIds`) so marking and unmarking always cover the whole entry.
 */
interface FavouritesRepository {
    /** The ids of every favourite book (copies included); a row whose book is gone is in here but never shown. */
    val favouriteIds: Flow<Set<String>>

    suspend fun add(bookIds: Collection<String>)

    suspend fun remove(bookIds: Collection<String>)

    /**
     * Unmarks every one of [bookIds] when any of them is a favourite, else marks them all. Returns whether they are
     * favourites now.
     */
    suspend fun toggle(bookIds: Collection<String>): Boolean
}

/** No favourites and nothing stored (tests and screens built without the repository). */
object NoFavourites : FavouritesRepository {
    override val favouriteIds: Flow<Set<String>> = flowOf(emptySet())
    override suspend fun add(bookIds: Collection<String>) = Unit
    override suspend fun remove(bookIds: Collection<String>) = Unit
    override suspend fun toggle(bookIds: Collection<String>): Boolean = false
}

/** In memory, for tests and previews. */
class InMemoryFavourites(initial: Set<String> = emptySet()) : FavouritesRepository {
    val ids = MutableStateFlow(initial)
    override val favouriteIds: Flow<Set<String>> = ids
    override suspend fun add(bookIds: Collection<String>) { ids.value = ids.value + bookIds }
    override suspend fun remove(bookIds: Collection<String>) { ids.value = ids.value - bookIds.toSet() }
    override suspend fun toggle(bookIds: Collection<String>): Boolean {
        val now = bookIds.none { it in ids.value }
        if (now) add(bookIds) else remove(bookIds)
        return now
    }
}

/** In the `favourites` table (Room v14). */
class RoomFavouritesRepository(
    private val dao: FavouriteDao,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : FavouritesRepository {

    override val favouriteIds: Flow<Set<String>> = dao.observeIds().map { it.toSet() }.distinctUntilChanged()

    override suspend fun add(bookIds: Collection<String>) {
        val at = nowMs()
        for (chunk in bookIds.distinct().chunked(CHUNK)) dao.insertAll(chunk.map { FavouriteEntity(it, at) })
    }

    override suspend fun remove(bookIds: Collection<String>) {
        for (chunk in bookIds.distinct().chunked(CHUNK)) dao.deleteAll(chunk)
    }

    override suspend fun toggle(bookIds: Collection<String>): Boolean {
        val ids = bookIds.distinct()
        if (ids.isEmpty()) return false
        val anyFavourite = ids.chunked(CHUNK).any { dao.existing(it).isNotEmpty() }
        if (anyFavourite) remove(ids) else add(ids)
        return !anyFavourite
    }

    private companion object {
        // SQLite allows at most 999 bound arguments.
        const val CHUNK = 500
    }
}
