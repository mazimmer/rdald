package com.rdald.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.rdald.app.service.PlaybackService

/**
 * MediaButtonReceiver - Handles media button events from Bluetooth headsets (AVRCP) and notification actions
 */
class MediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MediaButtonReceiver"
        const val EXTRA_KEYCODE = "com.rdald.app.extra.MediaButtonReceiver.KEYCODE"
        const val EXTRA_CUSTOM_ACTION = "com.rdald.app.extra.MediaButtonReceiver.CUSTOM_ACTION"
        const val EXTRA_SOURCE = "com.rdald.app.extra.MediaButtonReceiver.SOURCE"
        const val EXTRA_HARDWAREBUTTON = "com.rdald.app.extra.MediaButtonReceiver.HARDWAREBUTTON"
        const val PLAYBACK_SERVICE_INTENT = "com.rdald.app.intents.PLAYBACK_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive action: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                val event = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event != null && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val serviceIntent = Intent(PLAYBACK_SERVICE_INTENT)
                    serviceIntent.setPackage(context.packageName)
                    serviceIntent.putExtra(EXTRA_KEYCODE, event.keyCode)
                    serviceIntent.putExtra(EXTRA_SOURCE, event.source)
                    serviceIntent.putExtra(EXTRA_HARDWAREBUTTON, event.eventTime > 0 || event.downTime > 0)
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start PlaybackService", e)
                    }
                }
            }
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + android.view.KeyEvent.KEYCODE_MEDIA_STOP,
            PlaybackService.ACTION_MEDIA_CODE_PREFIX + android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
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
