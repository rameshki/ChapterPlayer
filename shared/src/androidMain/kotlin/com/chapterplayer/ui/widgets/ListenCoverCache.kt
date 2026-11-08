package com.chapterplayer.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.chapterplayer.data.images.AndroidCoverScaler
import com.chapterplayer.data.images.CoverBytesLoader
import com.chapterplayer.data.widgets.WidgetCovers
import com.chapterplayer.domain.widgets.CoverFetch
import com.chapterplayer.domain.widgets.WIDGET_COVER_MAX_SIDE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The Listen widget's covers as small bitmaps, kept in memory per book id (the last [maxEntries] books), so a redraw
 * never loads one again. [prefetch] runs in the app process, off the main thread (the refresher calls it, only while a
 * Listen widget is placed, never a redraw): [loader] is the authenticated, token-safe cover loader (the downloaded
 * cover first, else the book's own server with the credential in a header, 512 px JPEG), decoded here as RGB_565 to at
 * most [WIDGET_COVER_MAX_SIDE] px on the long side (128 KiB per cover, at most 384 KiB here). The widget only ever gets
 * the bitmap: no address, no token.
 */
class ListenCoverCache(
    private val loader: CoverBytesLoader,
    private val io: CoroutineDispatcher,
    private val maxEntries: Int = 3,
) : WidgetCovers {
    private val lock = Any()
    private val bitmaps = LinkedHashMap<String, Bitmap>()
    private val _version = MutableStateFlow(0)

    /** Moves on whenever a cover arrives, so a running widget session draws it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The cached cover of [bookId], or null (the widget then shows its placeholder). Never loads. */
    fun cached(bookId: String): Bitmap? = synchronized(lock) { bitmaps[bookId] }

    override suspend fun prefetch(bookId: String): CoverFetch {
        if (cached(bookId) != null) return CoverFetch.CACHED
        val bitmap = try {
            val bytes = loader.load(bookId) ?: return CoverFetch.FAILED
            withContext(io) { AndroidCoverScaler.decodeScaledBitmap(bytes, WIDGET_COVER_MAX_SIDE, Bitmap.Config.RGB_565) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return CoverFetch.FAILED
        synchronized(lock) {
            bitmaps.remove(bookId)
            bitmaps[bookId] = bitmap
            // An evicted bitmap is not recycled: a widget update may still be sending it.
            while (bitmaps.size > maxEntries) bitmaps.remove(bitmaps.keys.first())
        }
        _version.update { it + 1 }
        return CoverFetch.LOADED
    }
}

/** Whether a Listen widget is on the home screen. */
internal suspend fun listenWidgetPlaced(context: Context): Boolean =
    GlanceAppWidgetManager(context).getGlanceIds(ListenWidget::class.java).isNotEmpty()
