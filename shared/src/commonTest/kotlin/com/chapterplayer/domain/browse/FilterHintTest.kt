package com.chapterplayer.domain.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilterHintTest {

    @Test
    fun `no filter and a sort alone give no hint`() {
        assertNull(filterHintFor(BrowseQuery()))
        assertNull(filterHintFor(BrowseQuery(sort = BrowseSort.TITLE)))
    }

    @Test
    fun `a server filter alone keeps its own words`() {
        assertEquals(FilterHint("Server filter on", "Show all servers"), filterHintFor(BrowseQuery(serverIds = setOf("s1"))))
    }

    @Test
    fun `any other filter gives the general hint`() {
        val general = FilterHint("Filters on", "Clear filters")
        assertEquals(general, filterHintFor(BrowseQuery(progress = ProgressFilter.FINISHED)))
        assertEquals(general, filterHintFor(BrowseQuery(download = DownloadFilter.DOWNLOADED)))
        assertEquals(general, filterHintFor(BrowseQuery(favouritesOnly = true)))
        assertEquals(general, filterHintFor(BrowseQuery(progress = ProgressFilter.IN_PROGRESS, serverIds = setOf("s1"))))
    }

    @Test
    fun `clearing the filters keeps the sort`() {
        val query = BrowseQuery(ProgressFilter.FINISHED, DownloadFilter.DOWNLOADED, BrowseSort.AUTHOR, setOf("s1"), favouritesOnly = true)
        assertEquals(BrowseQuery(sort = BrowseSort.AUTHOR), withoutFilters(query))
    }
}
