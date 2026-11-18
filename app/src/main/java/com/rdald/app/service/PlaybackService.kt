package com.rdald.app.service
import com.rdald.app.R
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Playback service built on media3's MediaLibraryService, which is a MediaBrowserService
 * underneath (same as AntennaPod). Many OEM Bluetooth/AVRCP stacks only recognise media
 * apps that expose a MediaBrowserService, which is why a plain MediaSessionService never
 * became the "media button session" on this device and AVRCP presses were dropped.
 */
class PlaybackService : MediaLibraryService() {

    companion object {
        const val ACTION_MEDIA_KEY = "com.rdald.app.action.MEDIA_KEY"
        const val EXTRA_MEDIA_KEYCODE = "com.rdald.app.extra.MEDIA_KEYCODE"
        const val ACTION_LOAD_TEXT = "com.rdald.app.action.LOAD_TEXT"
        const val ACTION_APPLY_SETTINGS = "com.rdald.app.action.APPLY_SETTINGS"
        // Playback changed the library (e.g. an item finished and was archived).
        const val ACTION_LIBRARY_CHANGED = "com.rdald.app.action.LIBRARY_CHANGED"
        const val EXTRA_TEXT = "com.rdald.app.extra.TEXT"
        const val EXTRA_TITLE = "com.rdald.app.extra.TITLE"
        const val EXTRA_ID = "com.rdald.app.extra.ID"
        const val EXTRA_PROGRESS = "com.rdald.app.extra.PROGRESS"
        // Custom session command from the reader UI to swap the current voice.
        const val CMD_DESELECT_VOICE = "com.rdald.app.DESELECT_VOICE"
        // Custom session command: jump to the very end of the current item and finish it.
        const val CMD_FINISH_ITEM = "com.rdald.app.FINISH_ITEM"
    }

    private var mediaSession: MediaLibrarySession? = null
    private var player: TtsPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val p = TtsPlayer(this)
        player = p
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        p.setTtsSettings(
            prefs.getInt("speech_rate", 100) / 100f,
            prefs.getInt("speech_pitch", 100) / 100f
        )

        val callback = object : MediaLibrarySession.Callback {
            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                )
            }

            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                // Hardware / Bluetooth key events only (in-app buttons don't come through here).
                val skipArticles = getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("bt_skip_articles", false)
                if (skipArticles) {
                    val event = IntentCompat.getParcelableExtra(
                        intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java
                    )
                    if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                player?.skipToNextArticle()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                player?.skipToPreviousArticle()
                                return true
                            }
                        }
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                // Advertise our custom command so MediaController.sendCustomCommand reaches us.
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_DESELECT_VOICE, Bundle.EMPTY))
                    .add(SessionCommand(CMD_FINISH_ITEM, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == CMD_DESELECT_VOICE) {
                    player?.deselectCurrentVoiceAndSwitch()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction == CMD_FINISH_ITEM) {
                    player?.finishCurrentItem()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }

        mediaSession = MediaLibrarySession.Builder(this, p, callback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.rdald.app.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MEDIA_KEY -> {
                val code = intent.getIntExtra(EXTRA_MEDIA_KEYCODE, -1)
                if (code != -1) player?.onMediaKey(code)
                return START_STICKY
            }
            ACTION_LOAD_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                val id = intent.getStringExtra(EXTRA_ID) ?: ""
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                player?.playText(title, text, id, progress, announceTime = true)
                return START_STICKY
            }
            ACTION_APPLY_SETTINGS -> {
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                player?.setTtsSettings(
                    prefs.getInt("speech_rate", 100) / 100f,
                    prefs.getInt("speech_pitch", 100) / 100f
                )
                return START_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
