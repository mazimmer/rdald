package com.rdald.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.rdald.app.databinding.ActivityReaderBinding
import com.rdald.app.service.PlaybackService

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private var savedText: SavedText? = null

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateUi()
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
        setupControls()
        connectController()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = savedText?.title ?: "RdAld"
    }

    private fun setupTextDisplay() {
        binding.tvContent.text = savedText?.content
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener { togglePlayback() }
        binding.btnForward.setOnClickListener { controller?.seekForward() }
        binding.btnRewind.setOnClickListener { controller?.seekBack() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val c = controller ?: return
                val count = c.mediaItemCount
                if (count > 0) c.seekTo(count * seekBar.progress / 100, 0L)
            }
        })
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(playerListener)
            loadText(c)
        }, MoreExecutors.directExecutor())
    }

    private fun loadText(c: MediaController) {
        val st = savedText ?: return
        // Let the service own the (per-sentence) playlist. Pushing a single MediaItem
        // here made the session's playlist (1 item) disagree with the player's (N items),
        // which crashed media3 when another controller (AVRCP/SystemUI) connected.
        val i = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_LOAD_TEXT
            putExtra(PlaybackService.EXTRA_TEXT, st.content)
            putExtra(PlaybackService.EXTRA_TITLE, st.title)
        }
        startService(i)
    }

    private fun togglePlayback() {
        val c = controller ?: run {
            Toast.makeText(this, "Playback not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun updateUi() {
        val c = controller ?: return
        binding.btnPlayPause.setImageResource(
            if (c.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
        )
        binding.btnPlayPause.contentDescription =
            if (c.isPlaying) getString(R.string.action_pause) else getString(R.string.action_play)
        val count = c.mediaItemCount
        if (count > 0) {
            binding.seekBar.progress = (c.currentMediaItemIndex.coerceAtLeast(0) * 100) / count
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onDestroy()
    }
}
