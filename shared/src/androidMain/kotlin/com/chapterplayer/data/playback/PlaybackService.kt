package com.chapterplayer.data.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.repository.ServerRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Hosts the app's one player. Screens, the notification, the lock screen and (later) Android Auto all
 * talk to it through a Media3 session. It keeps playing when the app is swiped away, and stops itself
 * after the player has been paused for [PAUSED_STOP_AFTER_MS].
 */
@UnstableApi
class PlaybackService : MediaLibraryService(), KoinComponent {

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pausedStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(AuthorizingDataSourceFactory(get<ServerRepository>(), get<AbsAuthorizer>()))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                pausedStopJob?.cancel()
                pausedStopJob = null
                if (!playWhenReady) {
                    pausedStopJob = scope.launch {
                        delay(PAUSED_STOP_AFTER_MS)
                        pauseAllPlayersAndStopSelf()
                    }
                }
            }
        })

        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val builder = MediaLibrarySession.Builder(this, SkipByTimePlayer(exo), LibraryCallback())
        if (openApp != null) builder.setSessionActivity(openApp)
        session = builder.build().also {
            // The notification and the lock screen show 15 s back and 30 s forward, not file skipping.
            it.setMediaButtonPreferences(
                ImmutableList.of(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setDisplayName("Back 15 seconds")
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setDisplayName("Forward 30 seconds")
                        .build(),
                )
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the app is swiped away; with nothing playing there is no reason to stay.
        val p = player
        if (p == null || !p.playWhenReady) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    /** Only the root item for now: Android Auto's browse tree arrives in a later step. */
    private class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }
    }

    /** Hides next/previous file: the files of one book are one continuous recording. */
    private class SkipByTimePlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .removeAll(
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    }

    private companion object {
        const val ROOT_ID = "root"
        const val SEEK_BACK_MS = 15_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val PAUSED_STOP_AFTER_MS = 10 * 60 * 1000L
    }
}
