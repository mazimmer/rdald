package com.rdald.app.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
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
        if (intent?.action == ACTION_MEDIA_KEY) {
            val code = intent.getIntExtra(EXTRA_MEDIA_KEYCODE, -1)
            if (code != -1) player?.onMediaKey(code)
            return START_STICKY
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
