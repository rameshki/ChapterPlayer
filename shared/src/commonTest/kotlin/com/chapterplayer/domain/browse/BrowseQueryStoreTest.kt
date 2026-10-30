package com.chapterplayer.domain.browse

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseQueryStoreTest {

    @Test
    fun `a new store holds the default query`() {
        assertEquals(BrowseQuery(), BrowseQueryStore().query.value)
    }

    @Test
    fun `update applies the transform to the current query`() {
        val store = BrowseQueryStore()
        store.update { it.copy(progress = ProgressFilter.FINISHED) }
        store.update { it.copy(sort = BrowseSort.TITLE) }

        assertEquals(BrowseQuery(progress = ProgressFilter.FINISHED, sort = BrowseSort.TITLE), store.query.value)
    }

    @Test
    fun `the state flow always holds the latest query`() {
        val store = BrowseQueryStore()
        val flow = store.query
        store.update { it.copy(download = DownloadFilter.DOWNLOADED) }

        assertEquals(DownloadFilter.DOWNLOADED, flow.value.download)
    }
}
