package com.chapterplayer.ui.widgets

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.chapterplayer.data.widgets.WidgetResumer
import com.chapterplayer.domain.playback.EXTRA_OPEN_PLAYER
import com.chapterplayer.domain.widgets.WidgetStartReaction
import com.chapterplayer.domain.widgets.openAppAfterForegroundRefusal
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** The book a widget's Play starts (the one the widget shows). */
internal val WidgetPlayBookKey = ActionParameters.Key<String>("book_id")

/** The Play button of a widget's Resume card: starts [bookId] without opening the app ([WidgetPlayAction]). */
internal fun widgetPlayAction(bookId: String) = actionRunCallback<WidgetPlayAction>(actionParametersOf(WidgetPlayBookKey to bookId))

/**
 * A widget's Play with nothing loaded (Listen, Continue Listening, Now Playing): the book starts HEADLESS, in the app scope
 * through [WidgetResumer.launchHeadless] (so through PlaybackStarter, the copy the widget offers: a downloaded one first,
 * never a question). MainActivity is not started; the widgets flip to their controls once the player reports the book.
 *
 * Android 12+ forbids starting a foreground service from the background, but "the user performs an action on a UI
 * element related to your app ... a widget" is a documented exemption (developer.android.com, "Restrictions on starting
 * a foreground service from the background", exemptions). This tap is that action, and the start begins at once inside
 * it; Media3 then starts the playback service in the foreground when the book plays. Should the system still refuse
 * (the documented window is short and not specified, and a slow server can outlast it), Media3 reports it through
 * `MediaSessionService.Listener.onForegroundServiceStartNotAllowedException` and [HeadlessStarts.onForegroundRefused]
 * opens the app, which only then is needed. A failed start shows a short toast and keeps the Resume card; the app opens
 * only when the person must act there (sign in again).
 */
class WidgetPlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val bookId = parameters[WidgetPlayBookKey] ?: return
        val app = context.applicationContext
        val koin = object : KoinComponent {}
        koin.get<HeadlessStarts>().widgetStartRequested()
        koin.get<WidgetResumer>().launchHeadless(bookId) { reaction -> react(app, reaction) }
    }
}

/** A failed widget start: the short reason, and the app only when the person must act in it. Main thread. */
internal fun react(context: Context, reaction: WidgetStartReaction) {
    try {
        Toast.makeText(context, reaction.text, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        // No toast (a system that refuses it): the widget still shows its Resume card.
    }
    if (reaction.openApp) startApp(context, openPlayer = false)
}

/** Opens the app from the background; a system refusal is ignored (the toast already said why). */
internal fun startApp(context: Context, openPlayer: Boolean) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    if (openPlayer) intent.putExtra(EXTRA_OPEN_PLAYER, true)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

/**
 * Remembers the last widget Play so a refused foreground start right after it opens the app (the fallback of
 * [WidgetPlayAction]); a refusal for anything else (the car, a headset button) opens nothing.
 */
class HeadlessStarts(private val context: Context) {
    private fun now(): Long = SystemClock.elapsedRealtime()

    @Volatile private var lastWidgetStartMs: Long? = null

    fun widgetStartRequested() {
        lastWidgetStartMs = now()
    }

    /** Media3 could not start the playback service in the foreground (Android 12+). Called on the main thread. */
    fun onForegroundRefused() {
        if (!openAppAfterForegroundRefusal(now(), lastWidgetStartMs)) return
        lastWidgetStartMs = null
        // The book is loaded: the app opens on the player, which keeps the service alive and the notification follows.
        startApp(context, openPlayer = true)
    }
}
