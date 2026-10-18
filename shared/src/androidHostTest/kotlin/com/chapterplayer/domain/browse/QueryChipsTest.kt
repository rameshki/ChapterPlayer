package com.chapterplayer.domain.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryChipsTest {

    @Test
    fun `every chip maps to a query that maps back to it`() {
        FilterChip.entries.forEach { chip ->
            assertEquals(chip, chipFor(queryFor(chip)), "round trip of $chip")
        }
    }

    @Test
    fun `the chips map to the expected filters`() {
        assertEquals(BrowseQuery(ProgressFilter.ALL, DownloadFilter.ALL), queryFor(FilterChip.ALL))
        assertEquals(BrowseQuery(ProgressFilter.ALL, DownloadFilter.DOWNLOADED), queryFor(FilterChip.DOWNLOADED))
        assertEquals(BrowseQuery(ProgressFilter.IN_PROGRESS, DownloadFilter.ALL), queryFor(FilterChip.IN_PROGRESS))
        assertEquals(BrowseQuery(ProgressFilter.NOT_STARTED, DownloadFilter.ALL), queryFor(FilterChip.NOT_STARTED))
        assertEquals(BrowseQuery(ProgressFilter.FINISHED, DownloadFilter.ALL), queryFor(FilterChip.FINISHED))
    }

    @Test
    fun `a chip keeps the sort of the current query and replaces both filters`() {
        val current = BrowseQuery(ProgressFilter.FINISHED, DownloadFilter.NOT_DOWNLOADED, BrowseSort.LENGTH)

        assertEquals(BrowseQuery(ProgressFilter.ALL, DownloadFilter.DOWNLOADED, BrowseSort.LENGTH), queryFor(FilterChip.DOWNLOADED, current))
        assertEquals(BrowseQuery(sort = BrowseSort.LENGTH), queryFor(FilterChip.ALL, current))
    }

    @Test
    fun `the sort never changes the chip`() {
        BrowseSort.entries.forEach { sort ->
            assertEquals(FilterChip.FINISHED, chipFor(BrowseQuery(ProgressFilter.FINISHED, sort = sort)))
        }
    }

    @Test
    fun `a combination no chip stands for gives no chip`() {
        assertNull(chipFor(BrowseQuery(ProgressFilter.IN_PROGRESS, DownloadFilter.DOWNLOADED)))
        assertNull(chipFor(BrowseQuery(ProgressFilter.FINISHED, DownloadFilter.NOT_DOWNLOADED)))
        assertNull(chipFor(BrowseQuery(ProgressFilter.ALL, DownloadFilter.NOT_DOWNLOADED)))
    }

    @Test
    fun `only filters count as filtering not the sort`() {
        assertFalse(isFiltering(BrowseQuery(sort = BrowseSort.TITLE)))
        assertTrue(isFiltering(BrowseQuery(download = DownloadFilter.NOT_DOWNLOADED)))
        assertTrue(isFiltering(BrowseQuery(progress = ProgressFilter.NOT_STARTED)))
    }
}
