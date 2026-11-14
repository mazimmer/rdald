package com.readaloud.app.v2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.readaloud.app.v2.databinding.ActivityReaderBinding
import com.readaloud.app.v2.service.PlaybackService
import java.util.Locale

class ReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityReaderBinding
    private var savedText: SavedText? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    
    // Service connection
    private var playbackService: PlaybackService? = null
    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            playbackService?.setPlaybackListener(playbackListener)
            isBound = true
            // Sync UI with service state
            if (savedText != null) {
                playbackService?.setTextTitle(savedText!!.title)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playbackService = null
            isBound = false
        }
    }

    // Playback listener for UI updates
    private val playbackListener = object : PlaybackService.PlaybackListener {
        override fun onPlay() {
            runOnUiThread { updatePlayPauseButton(true) }
        }
        override fun onPause() {
            runOnUiThread { updatePlayPauseButton(false) }
        }
        override fun onToggle() {
            runOnUiThread { 
                val isPlaying = playbackService?.let { it.isPlaying && !it.isPaused } ?: false
                updatePlayPauseButton(isPlaying)
            }
        }
        override fun onFastForward() {
            // TODO: Implement forward
        }
        override fun onRewind() {
            // TODO: Implement rewind
        }
        override fun onSeekTo(position: Int) {
            runOnUiThread { binding.seekBar.progress = position }
        }
        override fun onStop() {
            runOnUiThread { updatePlayPauseButton(false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get saved text from intent
        savedText = intent.getParcelableExtra("saved_text")
        if (savedText == null) {
            Toast.makeText(this, "No text to read", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupTextDisplay()
        setupTTS()
        setupControls()
        bindPlaybackService()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = savedText?.title
    }

    private fun setupTextDisplay() {
        binding.tvContent.text = savedText?.content
    }

    private fun setupTTS() {
        tts = TextToSpeech(this, this)
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener { togglePlayback() }
        binding.btnForward.setOnClickListener { forward() }
        binding.btnRewind.setOnClickListener { rewind() }
        
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Update TTS position if playing
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun bindPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun togglePlayback() {
        if (isTtsInitialized) {
            if (tts?.isSpeaking == true) {
                tts?.stop()
                playbackService?.handlePause()
            } else {
                speakText()
                playbackService?.handlePlay()
            }
        } else {
            Toast.makeText(this, "TTS not ready yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speakText() {
        val text = savedText?.content ?: return
        val utteranceId = java.util.UUID.randomUUID().toString()
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        playbackService?.setCurrentSentence(text.take(100))
    }

    private fun forward() {
        // TODO: Implement forward logic
        Toast.makeText(this, "Forward 30s", Toast.LENGTH_SHORT).show()
    }

    private fun rewind() {
        // TODO: Implement rewind logic
        Toast.makeText(this, "Rewind 10s", Toast.LENGTH_SHORT).show()
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
        )
        binding.btnPlayPause.contentDescription = if (isPlaying) "Pause" else "Play"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            val locale = Locale.getDefault()
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Language not supported", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "TTS initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
