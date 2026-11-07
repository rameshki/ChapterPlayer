package com.chapterplayer.data.upnext

import com.chapterplayer.data.local.dao.UpNextDao
import com.chapterplayer.data.local.entity.UpNextEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * "Up next", the person's queue of books to play after the current one, kept on this phone only (no server has a
 * queue to sync with). [queue] is the stored ids in order; the rules of what a change does (Play next, Add, move,
 * remove) are pure code in `domain/playback/UpNext.kt` and come in through [edit], one change at a time.
 */
interface UpNextRepository {
    /** The stored rows' book ids, first to play first. A row whose book is gone is in here until the queue advances. */
    val queue: Flow<List<String>>

    /** Applies [change] to the queue as it is stored now and stores the result; returns it. */
    suspend fun edit(change: (List<String>) -> List<String>): List<String>
}

/** No queue and nothing stored (tests and screens built without the repository). */
object NoUpNext : UpNextRepository {
    override val queue: Flow<List<String>> = flowOf(emptyList())
    override suspend fun edit(change: (List<String>) -> List<String>): List<String> = emptyList()
}

/** In memory, for tests and previews. */
class InMemoryUpNext(initial: List<String> = emptyList()) : UpNextRepository {
    val ids = MutableStateFlow(initial)
    override val queue: Flow<List<String>> = ids
    private val lock = Mutex()
    override suspend fun edit(change: (List<String>) -> List<String>): List<String> = lock.withLock {
        change(ids.value).distinct().also { ids.value = it }
    }
}

/** In the `up_next` table (Room v15). A row that stays keeps the time it was first queued. */
class RoomUpNextRepository(
    private val dao: UpNextDao,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : UpNextRepository {

    private val lock = Mutex()

    override val queue: Flow<List<String>> = dao.observeAll().map { rows -> rows.map { it.bookId } }.distinctUntilChanged()

    override suspend fun edit(change: (List<String>) -> List<String>): List<String> = lock.withLock {
        val rows = dao.getAll()
        val before = rows.map { it.bookId }
        val after = change(before).distinct()
        if (after != before) {
            val addedAt = rows.associate { it.bookId to it.addedAt }
            val now = nowMs()
            dao.replaceAll(after.mapIndexed { index, id -> UpNextEntity(id, index, addedAt[id] ?: now) })
        }
        after
    }
}
