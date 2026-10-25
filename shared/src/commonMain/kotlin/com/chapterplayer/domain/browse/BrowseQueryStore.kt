package com.chapterplayer.domain.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The browse filters and sort the user picked, shared by every browse screen: the Library
 * screen writes it, Home and Search read it. Kept in memory only, so it resets on restart.
 */
class BrowseQueryStore {
    private val _query = MutableStateFlow(BrowseQuery())
    val query: StateFlow<BrowseQuery> = _query

    fun update(transform: (BrowseQuery) -> BrowseQuery) {
        _query.update(transform)
    }

    /** Drops servers that no longer exist from the server choice (see [pruneServerFilter]). */
    fun retainServers(existingServerIds: Set<String>) {
        _query.update { pruneServerFilter(it, existingServerIds) }
    }

    /** Keeps the server choice to the servers in [serverIds] for as long as [scope] lives. */
    fun followServers(
        serverIds: Flow<Set<String>>,
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext
    ): Job = scope.launch(context) {
        serverIds.collect { retainServers(it) }
    }
}
