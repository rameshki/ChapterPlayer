package com.chapterplayer.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.chapterplayer.domain.covers.parseCoverUri
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The playback session's artwork loader (notification, lock screen, the car's Now Playing, Bluetooth). A track's
 * artwork is the app's own cover address (`content://<app>.covers/cover/<bookId>`, no token), which this loads in the
 * service through [covers] (the downloaded cover, else the book's own server with the token in a header). Any other
 * address is refused, so the session never fetches from a host on its own. Failures carry fixed messages only.
 */
@UnstableApi
class CoverArtworkBitmapLoader(
    private val authority: String,
    private val covers: suspend (bookId: String) -> ByteArray?,
    private val scope: CoroutineScope,
) : BitmapLoader {

    // The same cover is asked for on every notification update; a failed load is not kept (see [LastLoadCache]).
    private val lastLoad = LastLoadCache<Bitmap>()

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = background { decode(data) }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val bookId = parseCoverUri(uri.toString(), authority)
            ?: return Futures.immediateFailedFuture(IOException("Not a cover address of this app"))
        return lastLoad.get(bookId) { background { decode(covers(bookId) ?: throw IOException("No cover")) } }
    }

    private fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("The cover is not an image")

    private fun background(block: suspend () -> Bitmap): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        scope.launch(Dispatchers.IO) {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                result.cancel(false)
                throw e
            } catch (e: IOException) {
                result.setException(e)
            } catch (_: Throwable) {
                result.setException(IOException("No cover"))
            }
        }
        return result
    }
}
