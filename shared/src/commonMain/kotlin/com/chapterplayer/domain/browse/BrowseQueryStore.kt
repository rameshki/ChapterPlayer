package com.chapterplayer.domain.browse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
}
