package com.rdald.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.rdald.app.data.SavedTextDb
import com.rdald.app.databinding.ActivityMainBinding
import com.rdald.app.service.DownloadService
import com.rdald.app.service.PlaybackService
import com.rdald.app.service.TtsPlayer

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = SavedTextAdapter()
    private var showArchive = false
    // When true, the next list load scrolls to the newest item at the bottom.
    private var scrollToBottomPending = true

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateMiniPlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTabs()
        setupRecyclerView()
        setupActionBar()
        setupFab()
        setupMiniPlayer()
        connectController()
        requestNotificationPermission()
        seedIfEmpty()
        handleIntent(intent)
        loadTexts()
    }

    private fun setupTabs() {
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.inbox))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.archive))
        binding.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                showArchive = tab.position == 1
                adapter.clearSelection()
                scrollToBottomPending = true
                loadTexts()
                updateActionBar()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun setupActionBar() {
        adapter.onSelectionChanged = { updateActionBar() }
        binding.btnArchive.setOnClickListener { archiveSelected() }
        binding.btnDelete.setOnClickListener { deleteSelected() }
        updateActionBar()
    }

    private fun updateActionBar() {
        val n = adapter.selectedCount
        binding.actionBar.visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        // "Archive" only makes sense in the Inbox; in the Archive tab you can only delete.
        binding.btnArchive.visibility = if (showArchive) android.view.View.GONE else android.view.View.VISIBLE
        if (n > 0) binding.btnDelete.text = getString(R.string.delete_count, n)
        else binding.btnDelete.text = getString(R.string.delete)
        updateMiniPlayer()
    }

    private fun archiveSelected() {
        val ids = adapter.selectedItems().map { it.id }
        if (ids.isEmpty()) return
        SavedTextDb.get(this).setArchived(ids, true)
        adapter.clearSelection()
        loadTexts()
        Toast.makeText(this, R.string.moved_to_archive, Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelected() {
        val ids = adapter.selectedItems().map { it.id }
        if (ids.isEmpty()) return
        SavedTextDb.get(this).delete(ids)
        adapter.clearSelection()
        loadTexts()
        Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
    }

    private val libraryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // An item finished and was archived (or another library change from playback).
            loadTexts()
            updateMiniPlayer()
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadTexts()
            val err = intent.getStringExtra(DownloadService.EXTRA_ERROR)
            Toast.makeText(
                this@MainActivity,
                if (err == null) getString(R.string.text_added)
                else getString(R.string.download_failed, err),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, refreshReceiver,
            IntentFilter(DownloadService.ACTION_TEXTS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, libraryReceiver,
            IntentFilter(PlaybackService.ACTION_LIBRARY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        loadTexts()
        updateMiniPlayer()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(libraryReceiver)
        } catch (_: Exception) {
        }
    }

    private fun loadTexts() {
        val items = SavedTextDb.get(this).getAll(showArchive)
        adapter.currentPlayingId = TtsPlayer.activeItemId
        adapter.submit(items)
        if (scrollToBottomPending && items.isNotEmpty()) {
            binding.recyclerView.scrollToPosition(items.size - 1)
        }
        scrollToBottomPending = false
        updateSummary(items)
    }

    /** Show item count + total estimated listening time for the current tab. */
    private fun updateSummary(items: List<SavedText>) {
        if (items.isEmpty()) {
            binding.tvSummary.text = getString(if (showArchive) R.string.archive_empty else R.string.no_items)
            return
        }
        val rate = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("speech_rate", 100) / 100f
            .coerceAtLeast(0.5f)
        var totalSec = 0L
        var remainSec = 0L
        for (item in items) {
            val words = item.content.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            val itemSec = (words / (180.0 * rate) * 60.0).toLong()
            totalSec += itemSec
            remainSec += itemSec * (100 - item.progress.coerceIn(0, 100)) / 100
        }
        fun fmt(sec: Long): String {
            val min = kotlin.math.ceil(sec / 60.0).toInt().coerceAtLeast(1)
            return if (min >= 60) {
                val h = min / 60
                val m = min % 60
                if (m > 0) "$h h $m min" else "$h h"
            } else {
                "$min min"
            }
        }
        binding.tvSummary.text = if (items.size == 1) {
            getString(R.string.summary_item, items.size, fmt(totalSec), fmt(remainSec))
        } else {
            getString(R.string.summary_items, items.size, fmt(totalSec), fmt(remainSec))
        }
    }

    // ---- Mini player ----
    private fun setupMiniPlayer() {
        binding.btnMiniPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        binding.btnMiniForward.setOnClickListener { controller?.seekForward() }
        binding.btnMiniRewind.setOnClickListener { controller?.seekBack() }
        binding.miniPlayer.setOnClickListener { openPlayingItem() }
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
                updateMiniPlayer()
            } catch (_: Exception) {
            }
        }, MoreExecutors.directExecutor())
    }

    private fun openPlayingItem() {
        val id = TtsPlayer.activeItemId ?: return
        val st = SavedTextDb.get(this).getById(id) ?: return
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra("saved_text", st)
        }
        startActivity(intent)
    }

    private fun updateMiniPlayer() {
        val id = TtsPlayer.activeItemId
        val selecting = adapter.selectedCount > 0
        val ended = controller?.playbackState == Player.STATE_ENDED
        val show = id != null && !selecting && !ended
        binding.miniPlayer.visibility =
            if (show) android.view.View.VISIBLE else android.view.View.GONE
        if (show && id != null) {
            val st = SavedTextDb.get(this).getById(id)
            binding.tvMiniTitle.text = st?.title ?: getString(R.string.app_name)
            val playing = controller?.isPlaying == true
            binding.btnMiniPlayPause.setImageResource(
                if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play
            )
            binding.btnMiniPlayPause.contentDescription =
                getString(if (playing) R.string.action_pause else R.string.action_play)
        }
        val newId = if (show) id else null
        if (adapter.currentPlayingId != newId) {
            adapter.currentPlayingId = newId
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onDestroy()
    }

    private fun startDownload(url: String) {
        val i = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
        }
        startService(i)
        Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        // Title with a very small version suffix, e.g. "NaraTTS 0.7.8".
        val base = getString(R.string.app_name)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        if (version.isNotBlank()) {
            val title = SpannableString("$base $version")
            title.setSpan(
                RelativeSizeSpan(0.6f),
                base.length, title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            supportActionBar?.title = title
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_SEND == intent.action && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null && sharedText.isNotBlank()) {
                if (DownloadService.isUrl(sharedText)) {
                    startDownload(sharedText.trim())
                } else {
                    SavedTextDb.get(this).insert(
                        SavedText(getString(R.string.shared_text_title), sharedText, System.currentTimeMillis())
                    )
                    loadTexts()
                    Toast.makeText(this, R.string.text_added, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        // Animate item removals (e.g. a finished card leaving the inbox).
        binding.recyclerView.itemAnimator =
            androidx.recyclerview.widget.DefaultItemAnimator().apply {
                removeDuration = 250
                moveDuration = 250
            }

        // Drag-to-reorder using the handle (long-press is reserved for multi-select).
        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                return adapter.moveItem(from, to)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Persist the new order once the drag finishes.
                SavedTextDb.get(this@MainActivity).setOrder(adapter.currentItems().map { it.id })
            }
        }
        val touchHelper = ItemTouchHelper(dragCallback)
        touchHelper.attachToRecyclerView(binding.recyclerView)
        adapter.attachTouchHelper(touchHelper)
        adapter.setOnItemClickListener { savedText ->
            if (showArchive) {
                // Move back to the inbox (bottom of queue). Reset to 0% only if fully read;
                // otherwise keep the archived percentage. Then open & play like the inbox.
                val db = SavedTextDb.get(this)
                db.setArchived(listOf(savedText.id), false)
                val newProgress = if (savedText.progress >= 100) 0 else savedText.progress
                db.setProgress(savedText.id, newProgress)
                db.moveToBottom(savedText.id)
                loadTexts()
                val updated = db.getById(savedText.id) ?: savedText
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra("saved_text", updated)
                }
                startActivity(intent)
            } else {
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra("saved_text", savedText)
                }
                startActivity(intent)
            }
        }
    }

    private fun setupFab() {
        binding.fab.setOnClickListener { showAddTextDialog() }
    }

    private fun showAddTextDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_dialog_hint)
            setMinLines(5)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_text)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    if (DownloadService.isUrl(text)) {
                        startDownload(text)
                    } else {
                        SavedTextDb.get(this).insert(
                            SavedText(getString(R.string.custom_text_title), text, System.currentTimeMillis())
                        )
                        loadTexts()
                        Toast.makeText(this, R.string.text_added, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, R.string.text_empty, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun seedIfEmpty() {
        val db = SavedTextDb.get(this)
        if (db.count() > 0) return
        val samples = listOf(
            SavedText(
                title = "Sample: The Little Prince",
                content = "Once when I was six years old I saw a magnificent picture in a book, called True Stories from Nature, about the primeval forest. It was a picture of a boa constrictor in the act of swallowing an animal. Here is a copy of the drawing.",
                createdAt = System.currentTimeMillis() - 100000
            ),
            SavedText(
                title = "Sample: Alice in Wonderland",
                content = "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do: once or twice she had peeped into the book her sister was reading, but it had no pictures or conversations in it, 'and what is the use of a book,' thought Alice 'without pictures or conversations?'",
                createdAt = System.currentTimeMillis() - 200000
            ),
            SavedText(
                title = "Sample: Long Text for Testing",
                content = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.",
                createdAt = System.currentTimeMillis() - 300000
            )
        )
        samples.forEach { db.insert(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add -> {
                showAddTextDialog()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
