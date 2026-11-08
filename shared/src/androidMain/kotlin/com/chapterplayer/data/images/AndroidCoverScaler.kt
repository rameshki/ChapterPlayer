package com.chapterplayer.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chapterplayer.domain.covers.COVER_JPEG_QUALITY
import com.chapterplayer.domain.covers.COVER_MAX_BYTES
import com.chapterplayer.domain.covers.coverSampleSize
import com.chapterplayer.domain.covers.coverTargetSize
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Scales a cover to at most 512 px on the long side as JPEG (quality 85). It decodes the size first and then with a
 * power-of-two sample ([coverSampleSize]), so a huge cover never needs its full size in memory. Null when the bytes are
 * not an image. Blocking: run it off the main thread.
 */
object AndroidCoverScaler {
    fun downscale(bytes: ByteArray): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = coverTargetSize(bounds.outWidth, bounds.outHeight)
        if (target == null) {
            null
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight) }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (decoded == null) {
                null
            } else {
                val scaled = if (decoded.width == target.width && decoded.height == target.height) decoded
                else Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, out)
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        }
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }

    /**
     * [bytes] decoded as a bitmap of at most [maxSide] px on the long side (the Listen widget's cover), sampled while
     * decoding as [downscale] does. Null when they are not an image. Blocking: run it off the main thread.
     */
    fun decodeScaledBitmap(bytes: ByteArray, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = coverTargetSize(bounds.outWidth, bounds.outHeight, maxSide)
        if (target == null) {
            null
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { decoded ->
                if (decoded.width == target.width && decoded.height == target.height) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also { if (it !== decoded) decoded.recycle() }
                }
            }
        }
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }

    /** The file at [path] when it is a regular file of at most [COVER_MAX_BYTES], else null. Blocking. */
    fun readSmallFile(path: String): ByteArray? = try {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > COVER_MAX_BYTES) null else file.readBytes()
    } catch (_: Exception) {
        null
    }
}
