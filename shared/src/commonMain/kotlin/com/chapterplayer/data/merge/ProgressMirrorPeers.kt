package com.chapterplayer.data.merge

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.playback.MirrorPeer
import com.chapterplayer.domain.playback.mirrorGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The other copies of a book whose progress is kept in step with it (merged sources, Phase 2). */
interface ProgressMirrorPeers {
    /** The other copies of [bookId]; empty for a book on one server, with the setting off, or when they cannot be read. */
    suspend fun peersOf(bookId: String): List<MirrorPeer>

    /** [peersOf] for each of [bookIds] in one go (one look at the corrections and the switch), e.g. for a merge pass. */
    suspend fun peersOfAll(bookIds: Collection<String>): Map<String, List<MirrorPeer>> = bookIds.associateWith { peersOf(it) }

    /** Emits when the corrections or the switch change (not for the state at the start), so queued mirrors can be looked at again. */
    val changes: Flow<Unit> get() = emptyFlow()

    /** No mirroring (tests and code paths without it). */
    object None : ProgressMirrorPeers {
        override suspend fun peersOf(bookId: String): List<MirrorPeer> = emptyList()
    }
}

/**
 * The copies as the lists group them ([mirrorGroups] over the cached library and the user's corrections), while
 * [enabled] (the Settings switch) is on. The corrections and the switch are read on every call, so a split or the switch
 * turned off stops mirroring at once; the grouping is worked out again only when the library or the corrections changed.
 */
class MergedProgressPeers(
    books: Flow<List<AudioBook>>,
    private val overrides: Flow<MergeOverrides>,
    private val enabled: Flow<Boolean>,
    scope: CoroutineScope,
) : ProgressMirrorPeers {

    private val library = books.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private class Grouping(val books: List<AudioBook>, val overrides: MergeOverrides, val groups: Map<String, List<String>>, val byId: Map<String, AudioBook>)

    private val lock = Mutex()
    private var cached: Grouping? = null

    override val changes: Flow<Unit> = combine(overrides, enabled) { o, e -> o to e }.distinctUntilChanged().drop(1).map { }

    override suspend fun peersOf(bookId: String): List<MirrorPeer> = peersOfAll(listOf(bookId))[bookId].orEmpty()

    override suspend fun peersOfAll(bookIds: Collection<String>): Map<String, List<MirrorPeer>> = try {
        val grouping = currentGrouping()
        if (grouping == null) {
            emptyMap()
        } else {
            bookIds.associateWith { bookId ->
                grouping.groups[bookId].orEmpty().mapNotNull { id ->
                    grouping.byId[id]?.let { MirrorPeer(it.id, it.serverId, it.durationMs) }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Best effort: a copy that cannot be looked up is simply not mirrored this time.
        emptyMap()
    }

    /** The grouping as it is now; null with the switch off. */
    private suspend fun currentGrouping(): Grouping? {
        if (!enabled.first()) return null
        val all = library.first()
        val corrections = overrides.first()
        return lock.withLock {
            cached?.takeIf { it.books === all && it.overrides == corrections }
                ?: Grouping(all, corrections, mirrorGroups(all, corrections), all.associateBy { it.id }).also { cached = it }
        }
    }
}
