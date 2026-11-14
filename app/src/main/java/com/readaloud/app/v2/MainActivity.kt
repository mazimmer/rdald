package com.readaloud.app.v2

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.readaloud.app.v2.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = SavedTextAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        handleIntent(intent)
        loadSampleData()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_SEND == intent.action && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null && sharedText.isNotBlank()) {
                val newText = SavedText(
                    title = "Shared Text",
                    content = sharedText,
                    createdAt = System.currentTimeMillis()
                )
                adapter.addItem(newText)
                Toast.makeText(this, "Text added", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        adapter.setOnItemClickListener { savedText ->
            val intent = Intent(this, ReaderActivity::class.java).apply {
                putExtra("saved_text", savedText)
            }
            startActivity(intent)
        }
    }

    private fun loadSampleData() {
        // Add sample texts for testing
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
        adapter.submitList(samples)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add -> {
                Toast.makeText(this, "Add text (not implemented yet)", Toast.LENGTH_SHORT).show()
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
