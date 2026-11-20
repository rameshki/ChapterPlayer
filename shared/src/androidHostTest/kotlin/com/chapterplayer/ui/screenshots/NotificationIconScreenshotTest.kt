package com.chapterplayer.ui.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.test.core.app.ApplicationProvider
import com.chapterplayer.shared.R
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of the notification small icon (review R29): the one-colour shape every ChapterPlayer notification
 * wears, as the system draws it (tinted white on the status bar's dark, dark grey on the light shade), at the real
 * 24 dp size and enlarged 4x.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-mdpi")
class NotificationIconScreenshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun icon() {
        val sizes = listOf(24, 96)
        val width = sizes.sum() + 16 * (sizes.size + 1)
        val image = Bitmap.createBitmap(width * 2, 96 + 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val half = Canvas(image)
        // left half on a dark status bar (white icon), right half on a light shade (dark grey icon)
        canvas.drawColor(Color.rgb(24, 24, 28))
        val light = android.graphics.Paint().apply { color = Color.rgb(240, 240, 244) }
        half.drawRect(width.toFloat(), 0f, (width * 2).toFloat(), image.height.toFloat(), light)
        var x = 16
        for (tint in listOf(Color.WHITE, Color.rgb(60, 60, 66))) {
            if (tint != Color.WHITE) x = width + 16
            for (size in sizes) {
                val drawable = DrawableCompat.wrap(ContextCompat.getDrawable(context, R.drawable.ic_notification)!!.mutate())
                DrawableCompat.setTint(drawable, tint)
                drawable.setBounds(x, 16, x + size, 16 + size)
                drawable.draw(canvas)
                x += size + 16
            }
        }
        image.captureRoboImage(screenshotPath("notification_icon"), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
