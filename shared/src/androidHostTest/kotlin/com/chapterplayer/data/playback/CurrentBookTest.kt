package com.chapterplayer.data.playback

import com.chapterplayer.domain.playback.TrackSpan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentBookTest {
    private val session = streamed(PlaybackSession("sess", "srv", "abs_a", 1_000, 0, emptyList(), emptyList()))
    private val spans = listOf(TrackSpan(0, 0, 1_000))

    @Test
    fun `a downloaded book playing offline is loaded without a server session`() {
        val b = CurrentBook()
        val offline = session.copy(session = null)
        b.begin(offline, spans)
        assertTrue(b.isLoaded)
        assertNull(b.session)
        assertEquals(offline, b.book)
        b.seen(300)
        assertEquals(offline to 300L, b.end())
    }

    @Test
    fun `the server session of the loaded book is exposed`() {
        val b = CurrentBook()
        b.begin(session, spans)
        assertEquals(session.session, b.session)
    }

    @Test
    fun `nothing is loaded until a book begins`() {
        val b = CurrentBook()
        assertFalse(b.isLoaded)
        b.seen(500)
        assertEquals(0L, b.lastPositionMs)
        assertNull(b.end())
    }

    @Test
    fun `ending hands back the session with the last position and forgets the book`() {
        val b = CurrentBook()
        b.begin(session, spans)
        assertTrue(b.isLoaded)
        b.seen(700)
        assertEquals(session to 700L, b.end())
        assertFalse(b.isLoaded)
        assertTrue(b.spans.isEmpty())
        assertEquals(0L, b.lastPositionMs)
        assertNull(b.end()) // a second end finds nothing to close
    }

    @Test
    fun `a book can begin again after it ended`() {
        val b = CurrentBook()
        b.begin(session, spans)
        b.end()
        b.begin(session, spans)
        assertTrue(b.isLoaded)
        assertEquals(spans, b.spans)
    }

    @Test
    fun `a failing block returns its failure`() = runTest {
        val failure = IllegalStateException("x")
        assertEquals(failure, runToEnd { throw failure })
        assertNull(runToEnd { })
    }

    @Test
    fun `a caller cancelled during the block still sees the block finish and then the cancellation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        var cleanedUpAsFailure = false
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            val failure = runToEnd {
                gate.await()
                finished = true
            }
            // Never reached for a cancelled caller: the cancellation is thrown on return.
            if (failure != null) cleanedUpAsFailure = true
        }
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertTrue(finished)
        assertFalse(cleanedUpAsFailure)
        assertTrue(job.isCancelled)
    }
}
