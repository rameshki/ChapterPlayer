package com.chapterplayer.data.merge

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.sync.ServerStatusStore
import com.chapterplayer.domain.browse.DEFAULT_SOURCE_PREFERENCE
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.reachableServerIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** What the merging of books on several servers needs: the corrections, the preference and which servers answer. */
interface SourceMerging {
    /** Null while merging is off (tests that do not care, and code paths without it). */
    val context: Flow<MergeContext?>

    /** No merging: every book is its own entry. */
    object None : SourceMerging {
        override val context: Flow<MergeContext?> = flowOf(null)
    }
}

/** The app's merging: the stored corrections, the Settings choice and the reachable servers right now. */
class AppSourceMerging(
    merges: BookMergeRepository,
    settings: AppSettings,
    connectivity: ConnectivityMonitor,
    servers: ServerRepository,
    statusStore: ServerStatusStore,
) : SourceMerging {

    private val reachable: Flow<Pair<Set<String>, Boolean>> = combine(
        servers.observeAll(), statusStore.all, connectivity.isOnline, settings.downloadedOnly
    ) { serverList, records, online, downloadedOnly ->
        reachableServerIds(serverList.map { it.id }, records, online, downloadedOnly) to online
    }.distinctUntilChanged()

    override val context: Flow<MergeContext?> = combine(merges.overrides, settings.sourcePreference, reachable) { overrides, preference, (ids, online) ->
        MergeContext(overrides, preference, ids, online)
    }.distinctUntilChanged()
}

/** A fixed context, for tests. */
class FixedSourceMerging(context: MergeContext = MergeContext(preference = DEFAULT_SOURCE_PREFERENCE)) : SourceMerging {
    override val context: Flow<MergeContext?> = flowOf(context)
}
