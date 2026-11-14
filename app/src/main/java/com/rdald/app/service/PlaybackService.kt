package com.rdald.app.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * media3 MediaSessionService — the same mechanism AntennaPod uses. As a real
 * MediaSessionService the system treats RdAld as a media app.
 *
 * The AppMediaButtonReceiver (a media3 MediaButtonReceiver subclass) forwards
 * ACTION_MEDIA_BUTTON intents here; we dispatch the key code into the player.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_MEDIA_KEY = "com.rdald.app.action.MEDIA_KEY"
        const val EXTRA_MEDIA_KEYCODE = "com.rdald.app.extra.MEDIA_KEYCODE"
    }

    private var mediaSession: MediaSession? = null
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

        mediaSession = MediaSession.Builder(this, p)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.rdald.app.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setCallback(object : MediaSession.Callback {
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
            })
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MEDIA_KEY) {
            val code = intent.getIntExtra(EXTRA_MEDIA_KEYCODE, -1)
            if (code != -1) player?.onMediaKey(code)
            return START_STICKY
        }
        // ACTION_MEDIA_BUTTON is handled by media3's MediaSessionService via the
        // AppMediaButtonReceiver; don't intercept it here to avoid double toggling.
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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
