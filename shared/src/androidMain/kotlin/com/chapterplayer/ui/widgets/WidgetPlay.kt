package com.chapterplayer.ui.widgets

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.chapterplayer.data.widgets.WidgetHost
import com.chapterplayer.data.widgets.WidgetResumer
import com.chapterplayer.di.AppScopeQualifier
import com.chapterplayer.domain.playback.EXTRA_OPEN_PLAYER
import com.chapterplayer.domain.widgets.ANOTHER_BOOK_PLAYING_TEXT
import com.chapterplayer.domain.widgets.WidgetStartReaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** The book a widget's Play starts (the one the widget shows). */
internal val WidgetPlayBookKey = ActionParameters.Key<String>("book_id")

/** The Play button of a widget's Resume card: starts [bookId] without opening the app ([WidgetPlayAction]). */
internal fun widgetPlayAction(bookId: String) = actionRunCallback<WidgetPlayAction>(actionParametersOf(WidgetPlayBookKey to bookId))

/**
 * A widget's Play with nothing loaded (Listen, Continue Listening, Now Playing): the book starts HEADLESS, through
 * [WidgetResumer.playFromWidget] (so through PlaybackStarter, the copy the widget offers: a downloaded one first, never a
 * question). MainActivity is not started; the widgets flip to their controls once the player reports the book. A book
 * already loaded is never replaced: a copy of the tapped book plays on, another book stays ("Another book is playing").
 *
 * The callback waits for the start (at most WIDGET_START_WAIT_MS): Glance runs it inside the widget's broadcast
 * (goAsync), so while it waits the process keeps the priority of a running receiver and is not frozen as a cached app in
 * the middle of the server calls. Android 12+ forbids starting a foreground service from the background, but "the user
 * performs an action on a UI element related to your app ... a widget" is a documented exemption (developer.android.com,
 * "Restrictions on starting a foreground service from the background"); Media3 starts the playback service in the
 * foreground when the book plays. Should the system still refuse, [PlaybackNotices.onForegroundRefused] pauses and posts
 * "Tap to keep listening" (an activity is never started from the background: Android 14+ blocks that silently).
 */
class WidgetPlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val bookId = parameters[WidgetPlayBookKey] ?: return
        val app = context.applicationContext
        val koin = object : KoinComponent {}
        try {
            koin.get<WidgetResumer>().playFromWidget(bookId) { reaction ->
                react(app, koin.get(), reaction)
                // A refused tap came from a stale card: draw the widgets again from the player's state.
                if (reaction.text == ANOTHER_BOOK_PLAYING_TEXT) redrawAll(koin.get(), koin.get(AppScopeQualifier))
            }
        } catch (e: CancellationException) {
            throw e // the broadcast went away; the start itself runs on in the app scope
        } catch (_: Exception) {
            // Koin or the resumer failed: nothing started, the widget keeps its card.
        }
    }
}

private fun redrawAll(host: WidgetHost, scope: CoroutineScope) {
    scope.launch {
        for (redraw in listOf<suspend () -> Unit>({ host.redrawListen() }, { host.redrawNowPlaying() }, { host.redrawContinueListening() })) {
            try {
                redraw()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

/** A failed widget start: the short reason; when the person must act in the app, a notification that opens it. Main thread. */
internal fun react(context: Context, notices: PlaybackNotices, reaction: WidgetStartReaction) {
    try {
        Toast.makeText(context, reaction.text, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        // No toast (a system that refuses it): the widget still shows its Resume card.
    }
    if (reaction.offerApp) notices.signInNeeded(reaction.text)
}

/**
 * Notifications the playback side posts when the app is not in front: a tap on one opens the app (a notification's
 * content intent is a legal way in from the background, unlike starting an activity). Channel "playback_notices".
 */
class PlaybackNotices(private val context: Context) {

    /**
     * Android refused to start the playback service in the foreground (Media3's
     * `onForegroundServiceStartNotAllowedException`): the caller paused the book; this says so and opens the player.
     */
    fun keepListening() = post(KEEP_LISTENING_ID, "Playback paused", "Tap to keep listening", openPlayer = true)

    /** A widget's Play needs the person to sign in again: the toast said so; this opens the app. */
    fun signInNeeded(text: String) = post(SIGN_IN_ID, "ChapterPlayer", text, openPlayer = false)

    private fun post(id: Int, title: String, text: String, openPlayer: Boolean) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            ensureChannel()
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            if (openPlayer) launch.putExtra(EXTRA_OPEN_PLAYER, true)
            val content = PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            manager.notify(id, notification)
        } catch (_: Exception) {
            // No permission or no notification service: the toast (or nothing) is all the person gets.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playback notices", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private companion object {
        const val CHANNEL_ID = "playback_notices"
        const val KEEP_LISTENING_ID = 0x7001
        const val SIGN_IN_ID = 0x7002
    }
}
