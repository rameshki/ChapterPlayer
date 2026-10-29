package com.chapterplayer.data.merge

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.playback.MirrorPeer
import com.chapterplayer.domain.playback.mirrorGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The other copies of a book whose progress is kept in step with it (merged sources, Phase 2). */
interface ProgressMirrorPeers {
    /** The other copies of [bookId]; empty for a book on one server, with the setting off, or when they cannot be read. */
    suspend fun peersOf(bookId: String): List<MirrorPeer>

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

    override suspend fun peersOf(bookId: String): List<MirrorPeer> = try {
        if (!enabled.first()) {
            emptyList()
        } else {
            val all = library.first()
            val corrections = overrides.first()
            val grouping = lock.withLock {
                cached?.takeIf { it.books === all && it.overrides == corrections }
                    ?: Grouping(all, corrections, mirrorGroups(all, corrections), all.associateBy { it.id }).also { cached = it }
            }
            grouping.groups[bookId].orEmpty().mapNotNull { id ->
                grouping.byId[id]?.let { MirrorPeer(it.id, it.serverId, it.durationMs) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Best effort: a copy that cannot be looked up is simply not mirrored this time.
        emptyList()
    }
}
