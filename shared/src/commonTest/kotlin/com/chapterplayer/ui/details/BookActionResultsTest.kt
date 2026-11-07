package com.chapterplayer.ui.details

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class BookActionResultsTest {

    private fun drain(results: BookActionResults): List<String> {
        val out = mutableListOf<String>()
        while (true) out += results.results.tryReceive().getOrNull() ?: return out
    }

    @Test
    fun `an earlier book's result still arrives after another book's menu opened`() = runTest {
        val results = BookActionResults(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val a = MutableStateFlow<String?>(null)
        val b = MutableStateFlow<String?>(null)
        results.follow("a", a) { a.value = null }
        results.follow("b", b) { b.value = null }
        b.value = "Added to favourites"
        a.value = "Marked as finished"
        assertEquals(listOf("Added to favourites", "Marked as finished"), drain(results))
        assertNull(a.value)
        assertNull(b.value)
    }

    @Test
    fun `a result left from when the screen was away is dropped and not shown later`() = runTest {
        val results = BookActionResults(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val a = MutableStateFlow<String?>("Marked as finished")
        results.follow("a", a) { a.value = null }
        assertEquals(emptyList(), drain(results))
        assertNull(a.value)
    }

    @Test
    fun `a book followed twice reports once`() = runTest {
        val results = BookActionResults(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val a = MutableStateFlow<String?>(null)
        results.follow("a", a) { a.value = null }
        results.follow("a", a) { a.value = null }
        a.value = "Download started"
        assertEquals(listOf("Download started"), drain(results))
    }
}
