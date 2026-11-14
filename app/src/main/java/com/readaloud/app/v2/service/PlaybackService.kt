package com.readaloud.app.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.readaloud.app.v2.R
import com.readaloud.app.v2.receiver.MediaButtonReceiver

/**
 * PlaybackService - Core media playback service with full AVRCP/Bluetooth support
 * 
 * Uses framework MediaSession (API 21+) with Notification.MediaStyle (framework)
 * - Framework MediaSession for playback control
 * - Framework MediaSession.Token for setMediaKeyEventSession and MediaStyle
 * - MediaSessionManager.setMediaKeyEventSession(MediaSession.Token) to claim AVRCP priority
 * - AudioFocus management for proper interruption handling
 * - MediaButtonReceiver that forwards KeyEvent to service
 * - Manual media button handling in onStartCommand
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "ReadAloudPlayback"
        const val CHANNEL_ID = "readaloud_player_v2"
        const val NOTIFICATION_ID = 101
        
        // Playback actions supported (framework constants)
        private const val PLAYBACK_ACTIONS = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO
        
        // Custom actions for notification buttons (matching AntennaPod's approach)
        const val ACTION_MEDIA_CODE_PREFIX = "MediaCode"
        const val ACTION_MEDIA_ACTION_PREFIX = "MediaAction"
        const val CUSTOM_ACTION_NEXT_CHAPTER = "next_chapter"
        const val CUSTOM_ACTION_TOGGLE_SLEEP_TIMER = "toggle_sleep_timer"
        
        // Playback state
        private var sInstance: PlaybackService? = null
        fun getInstance(): PlaybackService? = sInstance
    }

    // Framework MediaSession for playback
    private lateinit var mediaSession: MediaSession
    private var notificationManager: NotificationManager? = null
    private var playbackListener: PlaybackListener? = null
    
    // Playback state
    internal var isPlaying = false
    internal var isPaused = false
    internal var playbackPosition = 0L
    private var currentSentence: String? = null
    private var currentTextTitle: String = "ReadAloud"
    
    // Audio focus
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // Binder for activity communication
    private val binder = LocalBinder()

    interface PlaybackListener {
        fun onPlay()
        fun onPause()
        fun onToggle()
        fun onFastForward()
        fun onRewind()
        fun onSeekTo(position: Int)
        fun onStop()
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        Log.i(TAG, "PlaybackService onCreate")
        
        createNotificationChannel()
        initMediaSession()
        startForegroundService()
    }

    private fun createNotificationChannel() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_DEFAULT ensures notification appears in MEDIA section, not silent
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null) // No notification sound
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
            Log.i(TAG, "Created notification channel: $CHANNEL_ID")
        }
    }

    /**
     * Initialize framework MediaSession - CRITICAL for Bluetooth/AVRCP support
     * 
     * Key points:
     * 1. Framework MediaSession for playback control
     * 2. Framework MediaSession.Token for setMediaKeyEventSession and MediaStyle
     * 3. MediaSessionManager.setMediaKeyEventSession(MediaSession.Token) to CLAIM media button routing
     *    This is the secret sauce that makes Bluetooth headsets work!
     * 4. PlaybackState with all actions
     * 5. MediaMetadata for headset display
     */
    private fun initMediaSession() {
        // Create framework MediaSession (API 21+)
        mediaSession = MediaSession(this, "ReadAloudMediaSession").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or 
                     MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.i(TAG, "MediaSession.Callback.onPlay")
                    if (isPaused) playbackListener?.onToggle()
                }

                override fun onPause() {
                    Log.i(TAG, "MediaSession.Callback.onPause")
                    if (!isPaused) playbackListener?.onToggle()
                }

                override fun onFastForward() {
                    Log.i(TAG, "MediaSession.Callback.onFastForward")
                    playbackListener?.onFastForward()
                }

                override fun onRewind() {
                    Log.i(TAG, "MediaSession.Callback.onRewind")
                    playbackListener?.onRewind()
                }

                override fun onSkipToNext() {
                    Log.i(TAG, "MediaSession.Callback.onSkipToNext")
                    playbackListener?.onFastForward()
                }

                override fun onSkipToPrevious() {
                    Log.i(TAG, "MediaSession.Callback.onSkipToPrevious")
                    playbackListener?.onRewind()
                }

                override fun onSeekTo(pos: Long) {
                    Log.i(TAG, "MediaSession.Callback.onSeekTo: $pos")
                    playbackListener?.onSeekTo(pos.toInt())
                }

                override fun onStop() {
                    Log.i(TAG, "MediaSession.Callback.onStop")
                    playbackListener?.onStop()
                }
            })

            // Set media button receiver for modern Android (API 21+)
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            mediaButtonIntent.component = ComponentName(applicationContext, MediaButtonReceiver::class.java)
            val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                this@PlaybackService, 0, mediaButtonIntent, PendingIntent.FLAG_IMMUTABLE
            )
            setMediaButtonReceiver(mediaButtonPendingIntent)

            // Set initial playback state
            setPlaybackState(PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, 0L, 1.0f)
                .setActions(PLAYBACK_ACTIONS)
                .build())

            // Set metadata for headset display
            updateMetadata()
        }

        // CRITICAL: Claim media button routing for AVRCP (Bluetooth headset)
        // MediaSessionManager.setMediaKeyEventSession accepts framework MediaSession.Token since API 21
        // Use reflection in case it's not available in SDK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            try {
                val method = mediaSessionManager.javaClass.getMethod("setMediaKeyEventSession", android.media.session.MediaSession.Token::class.java)
                method.invoke(mediaSessionManager, mediaSession.sessionToken)
                Log.i(TAG, "setMediaKeyEventSession called via reflection - CLAIMED media button routing for AVRCP")
            } catch (e: Exception) {
                Log.w(TAG, "setMediaKeyEventSession not available or failed: ${e.message}")
            }
        }

        // Legacy media button registration (for older Android)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.registerMediaButtonEventReceiver(ComponentName(applicationContext, MediaButtonReceiver::class.java))

        // Activate the session
        mediaSession.isActive = true
        Log.i(TAG, "Framework MediaSession initialized with token for AVRCP")
    }

    private fun updateMetadata() {
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentSentence ?: currentTextTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "ReadAloud")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Text-to-Speech")
            .build())
    }

    private fun startForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Foreground service started with notification ID: $NOTIFICATION_ID")
    }

    private fun buildNotification(): Notification {
        val isPlayingNow = isPlaying && !isPaused
        
        // Build notification with framework Notification.Builder and Notification.MediaStyle (API 21+)
        // Using addAction(int, CharSequence, PendingIntent) - the old method that works
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTextTitle)
            .setContentText(if (isPlaying && !isPaused) getString(R.string.notification_playing) else getString(R.string.notification_paused))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, com.readaloud.app.v2.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .addAction(
                if (isPlaying && !isPaused) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                if (isPlaying && !isPaused) getString(R.string.action_pause) else getString(R.string.action_play),
                PendingIntent.getBroadcast(this, 101,
                    Intent(this, MediaButtonReceiver::class.java).apply {
                        action = if (isPlaying && !isPaused) ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_PAUSE
                                 else ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_PLAY
                        putExtra(MediaButtonReceiver.EXTRA_KEYCODE, if (isPlaying && !isPaused) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_media_fast_forward,
                getString(R.string.action_forward),
                PendingIntent.getBroadcast(this, 102,
                    Intent(this, MediaButtonReceiver::class.java).apply {
                        action = ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        putExtra(MediaButtonReceiver.EXTRA_KEYCODE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_media_rewind,
                getString(R.string.action_rewind),
                PendingIntent.getBroadcast(this, 103,
                    Intent(this, MediaButtonReceiver::class.java).apply {
                        action = ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_REWIND
                        putExtra(MediaButtonReceiver.EXTRA_KEYCODE, KeyEvent.KEYCODE_MEDIA_REWIND)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setStyle(Notification.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            )
            .setContentTitle(currentTextTitle)
            .setContentText(if (isPlaying && !isPaused) getString(R.string.notification_playing) else getString(R.string.notification_paused))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, com.readaloud.app.v2.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

        return builder.build()
    }

    fun setPlaybackListener(listener: PlaybackListener) {
        playbackListener = listener
    }

    fun setCurrentSentence(sentence: String?) {
        currentSentence = sentence
        updateMetadata()
        updateNotification()
    }

    fun setTextTitle(title: String) {
        currentTextTitle = title
        updateNotification()
    }

    /**
     * Request audio focus - called when playback starts
     */
    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.i(TAG, "onAudioFocusChange: $focusChange")
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (isPlaying && !isPaused) playbackListener?.onToggle()
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        abandonAudioFocus()
                        if (isPlaying) playbackListener?.onToggle()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {}
                }
            }
            .build()

        val result = audioManager?.requestAudioFocus(audioFocusRequest!!)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasAudioFocus = true
            Log.i(TAG, "Audio focus granted")
        } else {
            Log.w(TAG, "Audio focus request failed: $result")
        }
    }

    /**
     * Abandon audio focus - called when playback stops/pauses
     */
    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
        hasAudioFocus = false
        audioFocusRequest = null
        Log.i(TAG, "Audio focus abandoned")
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying && !isPaused) PlaybackState.STATE_PLAYING
        else if (isPaused) PlaybackState.STATE_PAUSED
        else PlaybackState.STATE_STOPPED

        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setState(state, playbackPosition, 1.0f)
            .setActions(PLAYBACK_ACTIONS)
            .build())
    }

    private fun updateNotification() {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            Log.i(TAG, "onStartCommand action: $action")
            when {
                action.startsWith(ACTION_MEDIA_CODE_PREFIX) -> {
                    val keyCodeStr = action.substring(ACTION_MEDIA_CODE_PREFIX.length)
                    val keyCode = keyCodeStr.toIntOrNull()
                    if (keyCode != null) handleMediaKeyCode(keyCode)
                }
                action.startsWith(ACTION_MEDIA_ACTION_PREFIX) -> {
                    val customAction = action.substring(ACTION_MEDIA_ACTION_PREFIX.length)
                    handleCustomAction(customAction)
                }
                action == Intent.ACTION_MEDIA_BUTTON -> {
                    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        handleMediaKeyCode(event.keyCode)
                    }
                }
                else -> {}
            }
        }
        return START_STICKY
    }

    private fun handleMediaKeyCode(keyCode: Int) {
        Log.i(TAG, "handleMediaKeyCode: $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (isPlaying && !isPaused) handlePause() else handlePlay()
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT -> playbackListener?.onFastForward()
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playbackListener?.onRewind()
            KeyEvent.KEYCODE_MEDIA_STOP -> handleStop()
        }
    }

    private fun handleCustomAction(action: String) {
        when (action) {
            CUSTOM_ACTION_NEXT_CHAPTER -> playbackListener?.onFastForward()
            CUSTOM_ACTION_TOGGLE_SLEEP_TIMER -> { /* TODO */ }
        }
    }

    internal fun handlePlay() {
        if (!isPlaying || isPaused) {
            requestAudioFocus()
            isPlaying = true
            isPaused = false
            playbackListener?.onPlay()
            updatePlaybackState()
            updateNotification()
        }
    }

    internal fun handlePause() {
        if (isPlaying && !isPaused) {
            isPaused = true
            abandonAudioFocus()
            playbackListener?.onPause()
            updatePlaybackState()
            updateNotification()
        }
    }

    internal fun handleStop() {
        if (isPlaying || isPaused) {
            isPlaying = false
            isPaused = false
            playbackPosition = 0
            abandonAudioFocus()
            playbackListener?.onStop()
            updatePlaybackState()
            updateNotification()
        }
    }

    fun setPlaybackState(playing: Boolean, paused: Boolean, position: Int) {
        Log.i(TAG, "setPlaybackState playing=$playing paused=$paused pos=$position")
        val wasPlaying = isPlaying && !isPaused
        val willPlay = playing && !paused
        
        isPlaying = playing
        isPaused = paused
        playbackPosition = position.toLong()
        
        if (!wasPlaying && willPlay) requestAudioFocus()
        else if (wasPlaying && !willPlay) abandonAudioFocus()
        
        updatePlaybackState()
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sInstance = null
        abandonAudioFocus()
        mediaSession.release()
        audioManager?.unregisterMediaButtonEventReceiver(ComponentName(applicationContext, MediaButtonReceiver::class.java))
        stopForeground(true)
        notificationManager?.cancel(NOTIFICATION_ID)
        super.onDestroy()
        Log.i(TAG, "PlaybackService destroyed")
    }
}
