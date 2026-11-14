package com.rdald.app.receiver

import androidx.media3.session.MediaButtonReceiver

/**
 * Media-button receiver. Extending media3's [MediaButtonReceiver] (rather than a plain
 * BroadcastReceiver) is what makes media3 install a real media-button PendingIntent on
 * the MediaSession. With a plain BroadcastReceiver that PendingIntent was null, so
 * Bluetooth AVRCP key presses were silently dropped. media3's own onReceive then
 * forwards the button to the MediaSessionService, which dispatches it to the player.
 */
class AppMediaButtonReceiver : MediaButtonReceiver()
