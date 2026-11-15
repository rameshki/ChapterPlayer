package com.chapterplayer.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.di.AppScopeQualifier
import com.chapterplayer.domain.downloads.BookProgress
import com.chapterplayer.domain.downloads.NetworkPolicy
import com.chapterplayer.domain.downloads.downloadNotificationText
import com.chapterplayer.domain.downloads.networkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** The unique WorkManager name of a book's download job. */
internal fun uniqueWorkName(bookId: String) = "download-$bookId"

internal const val KEY_BOOK_ID = "bookId"

/** Schedules the download jobs with WorkManager; the network constraint follows the Wi-Fi only setting. */
class WorkManagerDownloadScheduler(private val context: Context) : DownloadScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun schedule(bookId: String, wifiOnly: Boolean, replace: Boolean) {
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(uniqueWorkName(bookId), policy, request(bookId, wifiOnly, delayMs = 0)).await()
    }

    /** The dataSync time limit holds the book: its job is replaced by one that waits [delayMs] (or until the app opens). */
    override suspend fun scheduleLater(bookId: String, wifiOnly: Boolean, delayMs: Long) {
        workManager.enqueueUniqueWork(uniqueWorkName(bookId), ExistingWorkPolicy.REPLACE, request(bookId, wifiOnly, delayMs)).await()
    }

    private fun request(bookId: String, wifiOnly: Boolean, delayMs: Long): OneTimeWorkRequest {
        val network = when (networkPolicy(wifiOnly)) {
            NetworkPolicy.WIFI_ONLY -> NetworkType.UNMETERED
            NetworkPolicy.WIFI_AND_CELLULAR -> NetworkType.CONNECTED
        }
        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_BOOK_ID to bookId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(DOWNLOAD_TAG)
            .build()
    }

    override suspend fun cancel(bookId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(bookId)).await()
    }

    private companion object {
        const val DOWNLOAD_TAG = "download"
    }
}

/**
 * Runs one book's [BookDownloader] as a dataSync foreground job with a progress notification. When Android 15's
 * dataSync time limit (6 hours a day) stops it, WorkManager's foreground service gets `onTimeout` and stops the job with
 * `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`: the downloader saves the bytes and puts the book back in the queue, and
 * [DownloadTimeLimitHold] holds the queue and posts "Downloads paused" (or continues at once with the app in front).
 * If the system does not let the job run in the foreground right now (`ForegroundServiceStartNotAllowedException`:
 * background start limits, or the daily time used up), it runs as ordinary background work and WorkManager
 * reschedules it when the system stops it; the bytes are kept either way.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {

    private val downloader: BookDownloader by inject()
    private val bookDao: BookDao by inject()
    private val timeLimit: DownloadTimeLimitHold by inject()

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val title = bookDao.getById(bookId)?.title ?: DEFAULT_TITLE
        val notifications = DownloadNotifications(applicationContext)
        return try {
            coroutineScope {
                val updates = launch {
                    downloader.progress(bookId).filterNotNull().sample(NOTIFICATION_INTERVAL_MS).collect { progress ->
                        tryForeground(notifications.foregroundInfo(bookId, title, progress))
                    }
                }
                val result = try {
                    // Foreground only once this book has the download slot (a waiting book uses no foreground time).
                    downloader.run(bookId, onStart = { tryForeground(notifications.foregroundInfo(bookId, title, null)) })
                } finally {
                    updates.cancel()
                }
                if (result == BookResult.Retry) Result.retry() else Result.success()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // getStopReason is API 31+; the time limit exists only from Android 15.
                val timedOut = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT
                try {
                    timeLimit.afterStop(bookId, timedOut)
                } catch (_: Exception) {
                    // Best effort: the book is QUEUED with its bytes, and the next app start schedules it again.
                }
            }
            throw e
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val bookId = inputData.getString(KEY_BOOK_ID).orEmpty()
        val title = bookDao.getById(bookId)?.title ?: DEFAULT_TITLE
        return DownloadNotifications(applicationContext).foregroundInfo(bookId, title, null)
    }

    private suspend fun tryForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+, so not named here: minSdk 26) or IllegalStateException:
            // not allowed now (background start limits, or the daily dataSync time is used up). Carry on in the
            // background; WorkManager's foreground service also catches a refused startForeground itself, so nothing
            // crashes, and the bytes written so far stay in the .part file.
        }
    }

    private companion object {
        const val DEFAULT_TITLE = "Audiobook"
        const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}

/** The ongoing download notification: book title, "42% · 3h 10m left", Pause and Cancel. */
internal class DownloadNotifications(private val context: Context) {

    fun foregroundInfo(bookId: String, title: String, progress: BookProgress?): ForegroundInfo {
        ensureChannel()
        val percent = progress?.let { (it.fraction * 100).toInt().coerceIn(0, 100) } ?: 0
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(downloadNotificationText(progress))
            .setProgress(100, percent, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Pause", actionIntent(DownloadActionReceiver.ACTION_PAUSE, bookId))
            .addAction(0, "Cancel", actionIntent(DownloadActionReceiver.ACTION_CANCEL, bookId))
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        }
        val id = notificationId(bookId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, builder.build())
        }
    }

    private fun actionIntent(action: String, bookId: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.fromParts("download", bookId, null)) // one intent per book and action
            .putExtra(DownloadActionReceiver.EXTRA_BOOK_ID, bookId)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificationId(bookId: String) = DownloadNotificationIds.idFor(bookId)

    private companion object {
        const val CHANNEL_ID = "downloads"
    }
}

/**
 * Notification ids for downloads: each book gets the next free number the first time it asks, so two books
 * never share one in this process (a hash could collide). Ids start well away from the player's notification.
 */
internal object DownloadNotificationIds {
    private const val FIRST = 0x2000_0000
    private val ids = HashMap<String, Int>()

    @Synchronized
    fun idFor(bookId: String): Int = ids.getOrPut(bookId) { FIRST + ids.size }
}

/** Pause and Cancel from the download notification. */
class DownloadActionReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return
        val action = intent.action ?: return
        val controller = get<DownloadController>()
        val pending = goAsync()
        get<CoroutineScope>(AppScopeQualifier).launch {
            try {
                when (action) {
                    ACTION_PAUSE -> controller.pause(bookId)
                    ACTION_CANCEL -> controller.cancel(bookId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.chapterplayer.download.PAUSE"
        const val ACTION_CANCEL = "com.chapterplayer.download.CANCEL"
        const val EXTRA_BOOK_ID = "bookId"
    }
}
