package com.rdald.app

import android.Manifest
import android.content.BroadcastReceiver
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.rdald.app.data.SavedTextDb
import com.rdald.app.databinding.ActivityMainBinding
import com.rdald.app.service.DownloadService

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = SavedTextAdapter()
    private var showArchive = false
    // When true, the next list load scrolls to the newest item at the bottom.
    private var scrollToBottomPending = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTabs()
        setupRecyclerView()
        setupActionBar()
        setupFab()
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
        if (n > 0) binding.btnDelete.text = "Delete ($n)" else binding.btnDelete.text = getString(R.string.delete)
    }

    private fun archiveSelected() {
        val ids = adapter.selectedItems().map { it.id }
        if (ids.isEmpty()) return
        SavedTextDb.get(this).setArchived(ids, true)
        adapter.clearSelection()
        loadTexts()
        Toast.makeText(this, "Moved to archive", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelected() {
        val ids = adapter.selectedItems().map { it.id }
        if (ids.isEmpty()) return
        SavedTextDb.get(this).delete(ids)
        adapter.clearSelection()
        loadTexts()
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadTexts()
            val err = intent.getStringExtra(DownloadService.EXTRA_ERROR)
            Toast.makeText(
                this@MainActivity,
                if (err == null) "Text added" else "Download failed: $err",
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
        loadTexts()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
    }

    private fun loadTexts() {
        val items = SavedTextDb.get(this).getAll(showArchive)
        adapter.submitList(items) {
            if (scrollToBottomPending && items.isNotEmpty()) {
                binding.recyclerView.scrollToPosition(items.size - 1)
            }
            scrollToBottomPending = false
        }
        updateSummary(items)
    }

    /** Show item count + total estimated listening time for the current tab. */
    private fun updateSummary(items: List<SavedText>) {
        if (items.isEmpty()) {
            binding.tvSummary.text = if (showArchive) "Archive is empty" else "No items"
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
        val noun = if (items.size == 1) "item" else "items"
        binding.tvSummary.text =
            "${items.size} $noun · ~${fmt(totalSec)} total · ~${fmt(remainSec)} remaining"
    }

    private fun startDownload(url: String) {
        val i = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
        }
        startService(i)
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
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
                        SavedText("Shared Text", sharedText, System.currentTimeMillis())
                    )
                    loadTexts()
                    Toast.makeText(this, "Text added", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
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
            hint = "Paste or type text here"
            setMinLines(5)
        }
        AlertDialog.Builder(this)
            .setTitle("Add Text")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    if (DownloadService.isUrl(text)) {
                        startDownload(text)
                    } else {
                        SavedTextDb.get(this).insert(
                            SavedText("Custom Text", text, System.currentTimeMillis())
                        )
                        loadTexts()
                        Toast.makeText(this, "Text added", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Text cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
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
