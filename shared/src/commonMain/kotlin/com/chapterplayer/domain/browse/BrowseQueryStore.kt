package com.chapterplayer.domain.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The browse filters and sort the user picked, shared by every browse screen: the Library
 * screen writes it, Home and Search read it. Kept across launches through [persist] (the app wires it to
 * `AppSettings.browseFilters`); the search text is not part of it.
 */
/** How long the lists wait for the stored filters at start before they show the defaults. */
const val BROWSE_RESTORE_WAIT_MS = 1_500L

class BrowseQueryStore {
    private val _query = MutableStateFlow(BrowseQuery())
    val query: StateFlow<BrowseQuery> = _query

    /** False from [persist] until the stored filters were read (or [BROWSE_RESTORE_WAIT_MS] passed). */
    private val restored = MutableStateFlow(true)

    /**
     * [query] for the lists: it waits until the stored filters were read, so a list never shows the defaults for a
     * moment and then jumps to the restored filters. Without [persist] it gives the query at once.
     */
    val settledQuery: Flow<BrowseQuery> = flow {
        restored.first { it }
        emitAll(_query)
    }

    /** The person changed something, so the stored filters (read a moment later at start) must not replace it. */
    @Volatile
    private var changedByUser = false

    /** The last server list seen, so filters restored after it arrived are pruned too. */
    @Volatile
    private var knownServerIds: Set<String>? = null

    fun update(transform: (BrowseQuery) -> BrowseQuery) {
        changedByUser = true
        _query.update(transform)
    }

    /** Drops servers that no longer exist from the server choice (see [pruneServerFilter]). */
    fun retainServers(existingServerIds: Set<String>) {
        knownServerIds = existingServerIds
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

    /**
     * Restores the filters [stored] holds (see [decodeBrowseQuery]; a failed read or garbage leaves the defaults), unless
     * the person changed something first, then hands every change to [save] for as long as [scope] lives. Servers that
     * no longer exist are dropped as for any choice. A failed save is skipped; the next change saves again.
     */
    fun persist(
        stored: Flow<String?>,
        save: suspend (String) -> Unit,
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext
    ): Job {
        restored.value = false
        return scope.launch(context) { restoreThenSave(stored, save) }
    }

    private suspend fun restoreThenSave(stored: Flow<String?>, save: suspend (String) -> Unit) {
        // A read that does not answer soon leaves the defaults, so the lists are never held back for long.
        val saved = try {
            withTimeoutOrNull(BROWSE_RESTORE_WAIT_MS) { stored.first() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val restoredQuery = decodeBrowseQuery(saved)
        _query.update { current ->
            if (changedByUser) current else knownServerIds?.let { pruneServerFilter(restoredQuery, it) } ?: restoredQuery
        }
        restored.value = true
        // Nothing stored is the same as the defaults stored, so a new install writes nothing until a change.
        var lastSaved = saved ?: encodeBrowseQuery(BrowseQuery())
        _query.collect { query ->
            val encoded = encodeBrowseQuery(query)
            if (encoded == lastSaved) return@collect
            try {
                save(encoded)
                lastSaved = encoded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not saved this time; the next change tries again.
            }
        }
    }
}
