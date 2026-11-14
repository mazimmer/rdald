package com.rdald.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.rdald.app.R
import com.rdald.app.receiver.MediaButtonReceiver
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * PlaybackService - Core media playback service with full AVRCP/Bluetooth support
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "ReadAloudPlayback"
        const val CHANNEL_ID = "readaloud_player_v2"
        const val NOTIFICATION_ID = 101
        
        private const val PLAYBACK_ACTIONS = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO
        
        const val ACTION_MEDIA_CODE_PREFIX = "MediaCode"
        const val ACTION_MEDIA_ACTION_PREFIX = "MediaAction"
        const val CUSTOM_ACTION_NEXT_CHAPTER = "next_chapter"
        const val CUSTOM_ACTION_TOGGLE_SLEEP_TIMER = "toggle_sleep_timer"
        
        private var sInstance: PlaybackService? = null
        fun getInstance(): PlaybackService? = sInstance
    }

    private lateinit var mediaSession: MediaSession
    private var notificationManager: NotificationManager? = null
    private var playbackListener: PlaybackListener? = null
    
    internal var isPlaying = false
    internal var isPaused = false
    internal var playbackPosition = 0L
    private var currentSentence: String? = null
    private var currentTextTitle: String = "RdAld"
    private var fullText: String = ""
    
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private var tts: android.speech.tts.TextToSpeech? = null
    private var isTtsInitialized = false
    private var ttsUtteranceId: String? = null
    
    private val sentences = mutableListOf<String>()
    private var currentSentenceIndex = 0
    private val totalSentences = AtomicInteger(0)
    
    internal var forwardInterval = 30
    internal var rewindInterval = 10
    internal var speechRate = 1.0f
    internal var speechPitch = 1.0f

    private val binder = LocalBinder()

    interface PlaybackListener {
        fun onPlay()
        fun onPause()
        fun onToggle()
        fun onFastForward()
        fun onRewind()
        fun onSeekTo(position: Int)
        fun onStop()
        fun onProgressUpdate(current: Int, total: Int)
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
        initTTS()
        loadSettings()
        startForegroundService()
    }

    private fun createNotificationChannel() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
            Log.i(TAG, "Created notification channel: $CHANNEL_ID")
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "RdAldMediaSession").apply {
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

            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            mediaButtonIntent.component = ComponentName(applicationContext, MediaButtonReceiver::class.java)
            val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                this@PlaybackService, 0, mediaButtonIntent, PendingIntent.FLAG_IMMUTABLE
            )
            setMediaButtonReceiver(mediaButtonPendingIntent)

            setPlaybackState(PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, 0L, 1.0f)
                .setActions(PLAYBACK_ACTIONS)
                .build())
        }

        updateMetadata()

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

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.registerMediaButtonEventReceiver(ComponentName(applicationContext, MediaButtonReceiver::class.java))

        mediaSession.isActive = true
        Log.i(TAG, "Framework MediaSession initialized with token for AVRCP")
    }

    private fun initTTS() {
        tts = android.speech.tts.TextToSpeech(this, object : android.speech.tts.TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    isTtsInitialized = true
                    val locale = Locale.getDefault()
                    val result = tts?.setLanguage(locale)
                    if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Language not supported")
                    }
                    // Apply saved TTS settings
                    tts?.setSpeechRate(speechRate)
                    tts?.setPitch(speechPitch)
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "TTS onStart: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "TTS onDone: $utteranceId")
                            playNextSentence()
                        }

                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS onError: $utteranceId")
                            playNextSentence()
                        }
                    })
                    Log.i(TAG, "TTS initialized successfully with rate=$speechRate, pitch=$speechPitch")
                } else {
                    Log.e(TAG, "TTS initialization failed")
                }
            }
        })
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        forwardInterval = prefs.getInt("forward_interval", 30)
        rewindInterval = prefs.getInt("rewind_interval", 10)
        speechRate = prefs.getFloat("speech_rate", 100f) / 100f
        speechPitch = prefs.getFloat("speech_pitch", 100f) / 100f
    }

    fun applySettings() {
        loadSettings()
        if (isTtsInitialized && tts != null) {
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
            Log.i(TAG, "Applied TTS settings: rate=$speechRate, pitch=$speechPitch")
        }
        Log.i(TAG, "Applied settings: forward=${forwardInterval}s, rewind=${rewindInterval}s")
    }

    private fun updateMetadata() {
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentSentence ?: currentTextTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "RdAld")
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
        
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTextTitle)
            .setContentText(if (isPlaying && !isPaused) getString(R.string.notification_playing) else getString(R.string.notification_paused))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, com.rdald.app.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .addAction(
                if (isPlaying && !isPaused) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                if (isPlaying && !isPaused) getString(R.string.action_pause) else getString(R.string.action_play),
                PendingIntent.getForegroundService(this, 101,
                    Intent(this, PlaybackService::class.java).apply {
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
                PendingIntent.getForegroundService(this, 102,
                    Intent(this, PlaybackService::class.java).apply {
                        action = ACTION_MEDIA_CODE_PREFIX + KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        putExtra(MediaButtonReceiver.EXTRA_KEYCODE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_media_rewind,
                getString(R.string.action_rewind),
                PendingIntent.getForegroundService(this, 103,
                    Intent(this, PlaybackService::class.java).apply {
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
                Intent(this, com.rdald.app.MainActivity::class.java),
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

    fun setFullText(text: String) {
        fullText = text
        val sentences = text.split(Regex("[.!?]\\s+")).filter { it.isNotBlank() }
        this.sentences.clear()
        this.sentences.addAll(sentences)
        totalSentences.set(sentences.size)
        currentSentenceIndex = 0
        Log.i(TAG, "Text loaded: ${sentences.size} sentences")
    }

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
                        if (isPlaying && !isPaused) pauseTTS()
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        abandonAudioFocus()
                        if (isPlaying) stopPlayback()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (isPaused) resumeTTS()
                    }
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
                    val event = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event != null && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        handleMediaKeyCode(event.keyCode)
                    }
                }
                action == MediaButtonReceiver.PLAYBACK_SERVICE_INTENT -> {
                    // Handle media button events from MediaButtonReceiver
                    val keyCode = intent.getIntExtra(MediaButtonReceiver.EXTRA_KEYCODE, -1)
                    if (keyCode != -1) {
                        handleMediaKeyCode(keyCode)
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
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (isPlaying && !isPaused) handlePause() else handlePlay()
            }
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> handleFastForward()
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> handleRewind()
            android.view.KeyEvent.KEYCODE_MEDIA_STOP -> handleStop()
        }
    }

    private fun handleCustomAction(action: String) {
        when (action) {
            CUSTOM_ACTION_NEXT_CHAPTER -> handleFastForward()
            CUSTOM_ACTION_TOGGLE_SLEEP_TIMER -> { /* TODO */ }
        }
    }

    internal fun handlePlay() {
        if (!isPlaying || isPaused) {
            requestAudioFocus()
            isPlaying = true
            isPaused = false
            playbackListener?.onPlay()
            if (sentences.isNotEmpty() && !isTtsInitialized) {
                // Wait for TTS to initialize
            } else if (sentences.isNotEmpty()) {
                playNextSentence()
            }
            updatePlaybackState()
            updateNotification()
        }
    }

    internal fun handlePause() {
        if (isPlaying && !isPaused) {
            isPaused = true
            abandonAudioFocus()
            pauseTTS()
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
            stopTTS()
            sentences.clear()
            playbackListener?.onStop()
            updatePlaybackState()
            updateNotification()
        }
    }

    internal fun handleFastForward() {
        if (isTtsInitialized && tts?.isSpeaking == true) {
            tts?.stop()
            // Skip forward by advancing the index
            currentSentenceIndex = minOf(currentSentenceIndex + (forwardInterval / 5).coerceAtLeast(1), sentences.size - 1)
            playNextSentence()
        } else {
            playbackPosition += (forwardInterval * 1000L)
            playbackListener?.onFastForward()
            updatePlaybackState()
            updateNotification()
        }
    }

    internal fun handleRewind() {
        if (isTtsInitialized && tts?.isSpeaking == true) {
            tts?.stop()
            // Go back by rewinding the index
            val step = (rewindInterval / 5).coerceAtLeast(1)
            currentSentenceIndex = maxOf(0, currentSentenceIndex - step)
            // Play the sentence at the new index
            if (currentSentenceIndex < sentences.size) {
                currentSentence = sentences[currentSentenceIndex]
                playCurrentSentence()
            }
        } else {
            playbackPosition = maxOf(0L, playbackPosition - (rewindInterval * 1000L))
            playbackListener?.onRewind()
            updatePlaybackState()
            updateNotification()
        }
    }

    private fun playNextSentence() {
        if (currentSentenceIndex >= sentences.size) {
            handleStop()
            return
        }
        val sentence = sentences[currentSentenceIndex]
        currentSentence = sentence
        currentSentenceIndex++
        playbackListener?.onProgressUpdate(currentSentenceIndex, totalSentences.get())
        
        val utteranceId = java.util.UUID.randomUUID().toString()
        ttsUtteranceId = utteranceId
        
        val params = android.os.Bundle().apply {
            putString(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        
        updateMetadata()
        updateNotification()
        
        val result = tts?.speak(sentence, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == android.speech.tts.TextToSpeech.SUCCESS) {
            isPlaying = true
            isPaused = false
            updatePlaybackState()
            updateNotification()
        } else {
            Log.e(TAG, "TTS speak failed")
            playNextSentence()
        }
    }

    private fun playCurrentSentence() {
        if (currentSentenceIndex >= sentences.size) {
            handleStop()
            return
        }
        val sentence = sentences[currentSentenceIndex]
        currentSentence = sentence
        playbackListener?.onProgressUpdate(currentSentenceIndex + 1, totalSentences.get())
        
        val utteranceId = java.util.UUID.randomUUID().toString()
        ttsUtteranceId = utteranceId
        
        val params = android.os.Bundle().apply {
            putString(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        
        updateMetadata()
        updateNotification()
        
        val result = tts?.speak(sentence, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == android.speech.tts.TextToSpeech.SUCCESS) {
            isPlaying = true
            isPaused = false
            updatePlaybackState()
            updateNotification()
        } else {
            Log.e(TAG, "TTS speak failed")
            playNextSentence()
        }
    }

    private fun pauseTTS() {
        tts?.stop()
    }

    private fun resumeTTS() {
        if (currentSentence != null && isPaused) {
            val sentence = currentSentence!!
            val utteranceId = java.util.UUID.randomUUID().toString()
            ttsUtteranceId = utteranceId
            
            val params = android.os.Bundle().apply {
                putString(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            
            tts?.speak(sentence, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            isPlaying = true
            isPaused = false
            updatePlaybackState()
            updateNotification()
        }
    }

    private fun stopPlayback() {
        handleStop()
    }

    private fun stopTTS() {
        tts?.stop()
        tts?.shutdown()
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
        stopTTS()
        mediaSession.release()
        audioManager?.unregisterMediaButtonEventReceiver(ComponentName(applicationContext, MediaButtonReceiver::class.java))
        stopForeground(true)
        notificationManager?.cancel(NOTIFICATION_ID)
        super.onDestroy()
        Log.i(TAG, "PlaybackService destroyed")
    }
}
