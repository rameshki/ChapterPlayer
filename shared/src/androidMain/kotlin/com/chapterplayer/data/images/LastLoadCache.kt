package com.chapterplayer.data.images

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The last artwork load, handed out again for the same address while it is running or after it succeeded (the session
 * asks for the same cover on every notification update). Unlike Media3's `CacheBitmapLoader`, a failed or cancelled
 * load is never reused, so one failed fetch does not leave the whole book without a cover: the next update tries again
 * (the byte loader's own backoff keeps that from becoming a storm).
 */
class LastLoadCache<T> {
    private var key: String? = null
    private var last: ListenableFuture<T>? = null

    @Synchronized
    fun get(key: String, load: () -> ListenableFuture<T>): ListenableFuture<T> {
        val previous = last
        if (previous != null && this.key == key && reusable(previous)) return previous
        return load().also {
            this.key = key
            last = it
        }
    }

    private fun reusable(future: ListenableFuture<T>): Boolean {
        if (!future.isDone) return true
        if (future.isCancelled) return false
        return try {
            Futures.getDone(future)
            true
        } catch (_: Exception) {
            false
        }
    }
}
