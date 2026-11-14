package com.rdald.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.rdald.app.databinding.ActivityReaderBinding
import com.rdald.app.service.PlaybackService

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private var savedText: SavedText? = null
    private var bottomSheetBehavior: BottomSheetBehavior<android.widget.FrameLayout>? = null
    
    private var playbackService: PlaybackService? = null
    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            playbackService?.setPlaybackListener(playbackListener)
            isBound = true
            if (savedText != null) {
                playbackService?.setTextTitle(savedText!!.title)
                playbackService?.setFullText(savedText!!.content)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playbackService = null
            isBound = false
        }
    }

    private val playbackListener = object : PlaybackService.PlaybackListener {
        override fun onPlay() {
            runOnUiThread { updatePlayPauseButton(true) }
        }
        override fun onPause() {
            runOnUiThread { updatePlayPauseButton(false) }
        }
        override fun onToggle() {
            runOnUiThread { 
                val isPlaying = playbackService?.isPlaying == true && playbackService?.isPaused == false
                updatePlayPauseButton(isPlaying)
            }
        }
        override fun onFastForward() {
            runOnUiThread { Toast.makeText(this@ReaderActivity, "Forward ${playbackService?.forwardInterval ?: 30}s", Toast.LENGTH_SHORT).show() }
        }
        override fun onRewind() {
            runOnUiThread { Toast.makeText(this@ReaderActivity, "Rewind ${playbackService?.rewindInterval ?: 10}s", Toast.LENGTH_SHORT).show() }
        }
        override fun onSeekTo(position: Int) {
            runOnUiThread { binding.seekBar.progress = position }
        }
        override fun onStop() {
            runOnUiThread { updatePlayPauseButton(false) }
        }
        override fun onProgressUpdate(current: Int, total: Int) {
            runOnUiThread { 
                val progress = if (total > 0) (current * 100 / total) else 0
                binding.seekBar.progress = progress
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedText = intent.getParcelableExtra("saved_text")
        if (savedText == null) {
            Toast.makeText(this, "No text to read", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupTextDisplay()
        setupBottomSheet()
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

    private fun setupBottomSheet() {
        val bottomSheet = binding.bottomSheet
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetBehavior?.peekHeight = 200
        bottomSheetBehavior?.isHideable = false
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener { togglePlayback() }
        binding.btnForward.setOnClickListener { playbackService?.handleFastForward() }
        binding.btnRewind.setOnClickListener { playbackService?.handleRewind() }
        
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {}
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
        if (playbackService != null) {
            if (playbackService?.isPlaying == true && playbackService?.isPaused == false) {
                playbackService?.handlePause()
            } else {
                playbackService?.handlePlay()
            }
        } else {
            Toast.makeText(this, "Playback service not ready", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
        )
        binding.btnPlayPause.contentDescription = if (isPlaying) "Pause" else "Play"
    }

    override fun onDestroy() {
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
