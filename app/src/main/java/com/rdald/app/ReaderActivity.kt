package com.rdald.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.rdald.app.data.SavedTextDb
import com.rdald.app.databinding.ActivityReaderBinding
import com.rdald.app.service.PlaybackService
import com.rdald.app.service.TtsPlayer

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private var savedText: SavedText? = null

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var displayedId: String? = null

    private val paraViews = ArrayList<TextView>()
    private val paraTexts = ArrayList<String>()
    private val paraSentences = ArrayList<List<String>>()
    private var paraStartSentence: List<Int> = emptyList()
    private var sentenceParaOf: IntArray = IntArray(0)
    private var lastScrolledPara = -1
    private var lastHighlightedPara = -1
    private var lastHighlightedSentence = -1
    private val highlightColor = 0xFFFFF59D.toInt()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateUi()
            followActiveItem()
        }
    }

    private fun followActiveItem() {
        val active = TtsPlayer.activeItemId ?: return
        if (active == displayedId) return
        val st = SavedTextDb.get(this).getById(active) ?: return
        savedText = st
        displayedId = active
        supportActionBar?.title = st.title
        renderParagraphs(st.content)
        lastScrolledPara = -1
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedText = intent.getParcelableExtra("saved_text")
        if (savedText == null) {
            Toast.makeText(this, R.string.no_text_to_read, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        renderParagraphs(savedText!!.content)
        setupControls()
        connectController()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = savedText?.title ?: "NaraTTS"
        // Tapping the title bar opens the original article in the default browser.
        binding.toolbar.setOnClickListener { openSourceUrl() }
    }

    private fun openSourceUrl() {
        val url = savedText?.sourceUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.no_source_link, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_link_app, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.action_deselect_voice -> {
                deselectCurrentVoice()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Ask the service to drop the voice currently used for this item and pick another fitting,
     * still-selected voice. It re-reads the current sentence with the new voice.
     */
    private fun deselectCurrentVoice() {
        val c = controller
        if (c == null) {
            Toast.makeText(this, R.string.player_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            c.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_DESELECT_VOICE, Bundle.EMPTY),
                Bundle.EMPTY
            )
            Toast.makeText(this, R.string.voice_deselected, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.voice_change_failed, Toast.LENGTH_SHORT).show()
        }
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
                if (count <= 0) return
                if (seekBar.progress >= 100) {
                    // Dragged to the far right: jump to the very end and finish the item.
                    try {
                        c.sendCustomCommand(
                            SessionCommand(PlaybackService.CMD_FINISH_ITEM, Bundle.EMPTY),
                            Bundle.EMPTY
                        )
                    } catch (_: Exception) {
                    }
                } else {
                    val target = (count * seekBar.progress / 100).coerceIn(0, count - 1)
                    c.seekTo(target, 0L)
                }
            }
        })
    }

    /** Render paragraphs as tappable TextViews; tapping one jumps playback to that paragraph. */
    private fun renderParagraphs(content: String) {
        binding.paragraphContainer.removeAllViews()
        paraViews.clear()
        paraTexts.clear()
        paraSentences.clear()
        lastScrolledPara = -1
        lastHighlightedPara = -1
        lastHighlightedSentence = -1

        // Use the same segmentation as the player so paragraph/sentence indices stay in sync.
        val paras = TextSegmentation.paragraphs(content).ifEmpty { listOf(content.trim()) }

        val starts = ArrayList<Int>()
        val sentPara = ArrayList<Int>()
        var sentCount = 0
        paras.forEachIndexed { p, para ->
            starts.add(sentCount)
            val sents = TextSegmentation.sentences(para)
            paraSentences.add(sents)
            paraTexts.add(para)
            repeat(sents.size) { sentPara.add(p) }
            sentCount += sents.size
        }
        paraStartSentence = starts
        sentenceParaOf = sentPara.toIntArray()

        val padBottom = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
        ).toInt()
        paras.forEachIndexed { p, para ->
            val tv = TextView(this).apply {
                text = para
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Color.BLACK)
                setLineSpacing(0f, 1.5f)
                setPadding(0, 0, 0, padBottom)
                isClickable = true
                setOnClickListener { seekToParagraph(p) }
            }
            paraViews.add(tv)
            binding.paragraphContainer.addView(tv)
        }
    }

    private fun seekToParagraph(p: Int) {
        val start = paraStartSentence.getOrNull(p) ?: return
        controller?.seekTo(start, 0L)
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(playerListener)
            val active = TtsPlayer.activeItemId
            displayedId = savedText?.id
            if (active != null && active == savedText?.id) {
                updateUi()
            } else {
                loadText(c)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun loadText(c: MediaController) {
        val st = savedText ?: return
        val i = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_LOAD_TEXT
            putExtra(PlaybackService.EXTRA_TEXT, st.content)
            putExtra(PlaybackService.EXTRA_TITLE, st.title)
            putExtra(PlaybackService.EXTRA_ID, st.id)
            putExtra(PlaybackService.EXTRA_PROGRESS, st.progress)
        }
        startService(i)
    }

    private fun togglePlayback() {
        val c = controller ?: run {
            Toast.makeText(this, R.string.playback_not_ready, Toast.LENGTH_SHORT).show()
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
        val idx = c.currentMediaItemIndex
        if (count > 0) {
            binding.seekBar.progress = (idx.coerceAtLeast(0) * 100) / count
        }
        if (idx in 0 until sentenceParaOf.size) {
            highlightSentence(idx)
            val p = sentenceParaOf[idx]
            if (p != lastScrolledPara && p in paraViews.indices) {
                lastScrolledPara = p
                val v = paraViews[p]
                binding.scrollView.post { binding.scrollView.smoothScrollTo(0, v.top) }
            }
        }
    }

    /** Highlight the sentence currently being spoken. */
    private fun highlightSentence(idx: Int) {
        if (idx == lastHighlightedSentence) return
        if (idx < 0 || idx >= sentenceParaOf.size) return
        val p = sentenceParaOf[idx]
        if (p < 0 || p >= paraViews.size) return
        val local = idx - paraStartSentence.getOrElse(p) { 0 }
        val sents = paraSentences.getOrNull(p) ?: return
        if (local < 0 || local >= sents.size) return
        val paraText = paraTexts.getOrNull(p) ?: return
        val sentenceText = sents[local]
        val start = paraText.indexOf(sentenceText)
        val sp = SpannableString(paraText)
        if (start >= 0) {
            val end = start + sentenceText.length
            sp.setSpan(BackgroundColorSpan(Color.BLACK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        paraViews[p].setText(sp)
        if (lastHighlightedPara >= 0 && lastHighlightedPara != p && lastHighlightedPara < paraViews.size) {
            paraViews[lastHighlightedPara].setText(paraTexts[lastHighlightedPara])
        }
        lastHighlightedPara = p
        lastHighlightedSentence = idx
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
