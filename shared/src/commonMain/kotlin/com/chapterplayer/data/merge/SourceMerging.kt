package com.chapterplayer.data.merge

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.sync.ServerStatusStore
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.reachableServerIds
import com.chapterplayer.domain.browse.resolvedAgainst
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** What the merging of books on several servers needs: the corrections, the two source choices and which servers answer. */
interface SourceMerging {
    /** Null while merging is off (tests that do not care, and code paths without it). */
    val context: Flow<MergeContext?>

    /** No merging: every book is its own entry. */
    object None : SourceMerging {
        override val context: Flow<MergeContext?> = flowOf(null)
    }
}

/**
 * The app's merging: the stored corrections, the Settings choices (a choice of a server that was removed reads as Ask)
 * and the reachable servers right now.
 */
class AppSourceMerging(
    merges: BookMergeRepository,
    settings: AppSettings,
    connectivity: ConnectivityMonitor,
    servers: ServerRepository,
    statusStore: ServerStatusStore,
) : SourceMerging {

    private class Reach(val serverIds: Set<String>, val reachable: Set<String>, val online: Boolean)

    private val reachable: Flow<Reach> = combine(
        servers.observeAll(), statusStore.all, connectivity.isOnline, settings.downloadedOnly
    ) { serverList, records, online, downloadedOnly ->
        val ids = serverList.mapTo(LinkedHashSet()) { it.id }
        Reach(ids, reachableServerIds(ids, records, online, downloadedOnly), online)
    }

    override val context: Flow<MergeContext?> = combine(
        merges.overrides, settings.downloadSourceChoice, settings.playSourceChoice, reachable
    ) { overrides, download, play, reach ->
        MergeContext(
            overrides,
            downloadChoice = download.resolvedAgainst(reach.serverIds),
            playChoice = play.resolvedAgainst(reach.serverIds),
            onlineServerIds = reach.reachable,
            deviceOnline = reach.online,
        )
    }.distinctUntilChanged()
}

/** A fixed context, for tests. */
class FixedSourceMerging(context: MergeContext = MergeContext()) : SourceMerging {
    override val context: Flow<MergeContext?> = flowOf(context)
}
