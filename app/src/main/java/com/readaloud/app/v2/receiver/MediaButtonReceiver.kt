package com.readaloud.app.v2.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.readaloud.app.v2.service.PlaybackService

/**
 * MediaButtonReceiver - Handles media button events from Bluetooth headsets (AVRCP) and notification actions
 * 
 * Based on AntennaPod's MediaButtonReceiver:
 * 1. Receives ACTION_MEDIA_BUTTON intents from Bluetooth headsets
 * 2. Extracts KeyEvent and forwards to PlaybackService with keycode extras
 * 3. Handles custom actions from notification buttons (play/pause, forward, rewind)
 * 4. Routes everything to PlaybackService via startForegroundService
 */
class MediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MediaButtonReceiver"
        const val EXTRA_KEYCODE = "com.readaloud.app.v2.extra.MediaButtonReceiver.KEYCODE"
        const val EXTRA_CUSTOM_ACTION = "com.readaloud.app.v2.extra.MediaButtonReceiver.CUSTOM_ACTION"
        const val EXTRA_SOURCE = "com.readaloud.app.v2.extra.MediaButtonReceiver.SOURCE"
        const val EXTRA_HARDWAREBUTTON = "com.readaloud.app.v2.extra.MediaButtonReceiver.HARDWAREBUTTON"
        const val PLAYBACK_SERVICE_INTENT = "com.readaloud.app.v2.intents.PLAYBACK_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive action: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                // This is the key for AVRCP - handles Bluetooth headset buttons
                // Extract KeyEvent and forward to PlaybackService
                val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val serviceIntent = Intent(PLAYBACK_SERVICE_INTENT)
                    serviceIntent.setPackage(context.packageName)
                    serviceIntent.putExtra(EXTRA_KEYCODE, event.keyCode)
                    serviceIntent.putExtra(EXTRA_SOURCE, event.source)
                    serviceIntent.putExtra(EXTRA_HARDWAREBUTTON, event.eventTime > 0 || event.downTime > 0)
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start PlaybackService", e)
                    }
                }
            }
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_PLAY,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_PAUSE,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_REWIND,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_STOP,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_HEADSETHOOK -> {
                // Custom actions from notification buttons - forward to PlaybackService
                val serviceIntent = Intent(context, PlaybackService::class.java)
                serviceIntent.action = intent.action
                serviceIntent.putExtras(intent)
                context.startForegroundService(serviceIntent)
            }
            else -> {
                Log.w(TAG, "Unknown action received: ${intent.action}")
            }
        }
    }
}
