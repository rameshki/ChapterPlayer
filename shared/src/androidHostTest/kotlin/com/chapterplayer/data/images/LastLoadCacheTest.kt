package com.chapterplayer.data.images

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LastLoadCacheTest {

    private val cache = LastLoadCache<String>()
    private var loads = 0

    private fun load(result: () -> ListenableFuture<String>): () -> ListenableFuture<String> = { loads++; result() }

    @Test
    fun `a cover that loaded is handed out again for the same address`() {
        val first = cache.get("cover/abs_1", load { Futures.immediateFuture("bitmap") })
        val second = cache.get("cover/abs_1", load { Futures.immediateFuture("other") })
        assertSame(first, second)
        assertEquals(1, loads)
    }

    @Test
    fun `a load still running is shared`() {
        val pending = SettableFuture.create<String>()
        val first = cache.get("cover/abs_1", load { pending })
        assertSame(first, cache.get("cover/abs_1", load { Futures.immediateFuture("other") }))
        assertEquals(1, loads)
    }

    @Test
    fun `a failed load is never reused, so the next update tries again`() {
        val failed = cache.get("cover/abs_1", load { Futures.immediateFailedFuture(IOException("No cover")) })
        val again = cache.get("cover/abs_1", load { Futures.immediateFuture("bitmap") })
        assertNotSame(failed, again)
        assertEquals("bitmap", again.get())
        assertEquals(2, loads)
    }

    @Test
    fun `a cancelled load is never reused`() {
        val pending = SettableFuture.create<String>()
        cache.get("cover/abs_1", load { pending })
        pending.cancel(false)
        cache.get("cover/abs_1", load { Futures.immediateFuture("bitmap") })
        assertEquals(2, loads)
    }

    @Test
    fun `another address loads anew`() {
        cache.get("cover/abs_1", load { Futures.immediateFuture("one") })
        val other = cache.get("cover/abs_2", load { Futures.immediateFuture("two") })
        assertEquals("two", other.get())
        assertEquals(2, loads)
    }
}
