package com.chapterplayer.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chapterplayer.data.isAppInFront
import com.chapterplayer.domain.downloads.AfterJobStop
import com.chapterplayer.domain.downloads.releaseHoldOnAppOpen
import com.chapterplayer.domain.downloads.timeLimitNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Android 15's dataSync time limit (6 hours in 24) for the download job. When it stops a job with the app in the
 * background, the waiting downloads are held ([DefaultDownloadController.afterStop]), the hold is remembered on disk (the
 * process may die before the app is opened) and "Downloads paused" says that opening the app continues them. The app
 * coming to the front ([onAppVisible], from MainActivity.onStart) gives Android's budget back, so the hold is released
 * and the queue starts at once; otherwise the held jobs run by themselves after a day.
 */
class DownloadTimeLimitHold(
    private val context: Context,
    private val controller: DefaultDownloadController,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The job of [bookId] stopped; [timedOut] when the dataSync time limit stopped it. */
    suspend fun afterStop(bookId: String, timedOut: Boolean) {
        val followUp = controller.afterStop(bookId, timedOut, appVisible = isAppInFront())
        if (followUp.action == AfterJobStop.HOLD_UNTIL_APP_OPENS) {
            prefs.edit().putLong(KEY_HELD_AT, nowMs()).apply()
            postNotice(followUp.waitingBooks)
        }
    }

    /** The app came to the front: a recorded hold is released and the waiting downloads start again. Main thread. */
    fun onAppVisible() {
        val heldAt = if (prefs.contains(KEY_HELD_AT)) prefs.getLong(KEY_HELD_AT, 0L) else null
        if (!releaseHoldOnAppOpen(heldAt)) return
        prefs.edit().remove(KEY_HELD_AT).apply()
        try {
            NotificationManagerCompat.from(context).cancel(NOTICE_ID)
        } catch (_: Exception) {
        }
        scope.launch {
            try {
                controller.releaseTimeLimitHold()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The held jobs still run after their delay, and the next app start restores the queue.
            }
        }
    }

    private fun postNotice(waitingBooks: Int) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            ensureChannel()
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val content = PendingIntent.getActivity(context, NOTICE_ID, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notice = timeLimitNotice(waitingBooks)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            manager.notify(NOTICE_ID, notification)
        } catch (_: Exception) {
            // No permission or no notification service: the Downloads tab still shows the books as queued.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Download notices", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private companion object {
        const val PREFS = "downloads"
        const val KEY_HELD_AT = "time_limit_held_at"
        const val CHANNEL_ID = "download_notices"
        const val NOTICE_ID = 0x7101
    }
}
