package com.chapterplayer.data.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notifications on Android 13 and later. The open activity lends its permission launcher with [attach]
 * and passes the answer to [onResult]; with no activity open nothing is asked. The question is asked
 * once (remembered across launches); after that [ensure] only reports whether notifications are allowed.
 */
class AndroidNotificationPermission(private val context: Context) : NotificationPermission {

    private val lock = Mutex()
    @Volatile private var ask: (() -> Unit)? = null
    @Volatile private var answer: CompletableDeferred<Boolean>? = null

    /** The activity's way to show the system question; call from onCreate. */
    fun attach(ask: () -> Unit) {
        this.ask = ask
    }

    /** Call from onDestroy with the same function given to [attach]. */
    fun detach(ask: () -> Unit) {
        if (this.ask === ask) this.ask = null
    }

    /** The system's answer, from the activity's result callback (also after the activity was re-created). */
    fun onResult(granted: Boolean) {
        answer?.complete(granted)
    }

    override suspend fun ensure(): Boolean = lock.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted()) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return false
        val show = ask ?: return false
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        val pending = CompletableDeferred<Boolean>()
        answer = pending
        try {
            withContext(Dispatchers.Main) { show() }
            // The answer may never come if the activity goes away for good; then ask the system.
            withTimeoutOrNull(ANSWER_TIMEOUT_MS) { pending.await() } ?: granted()
        } finally {
            answer = null
        }
    }

    private fun granted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFS = "permissions"
        const val KEY_ASKED = "asked_notifications_for_downloads"
        const val ANSWER_TIMEOUT_MS = 120_000L
    }
}
