package com.rdald.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.rdald.app.data.SavedTextDb
import com.rdald.app.service.DownloadService

/**
 * Invisible share target. Receives ACTION_SEND text/plain from other apps and
 * stores it (or starts a background download) without bringing the app UI up.
 * Uses Theme.NoDisplay, so it must finish() before onResume and show no UI.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent != null && Intent.ACTION_SEND == intent.action && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                if (DownloadService.isUrl(sharedText)) {
                    startService(
                        Intent(this, DownloadService::class.java).apply {
                            action = DownloadService.ACTION_DOWNLOAD
                            putExtra(DownloadService.EXTRA_URL, sharedText.trim())
                        }
                    )
                    Toast.makeText(this, "Adding to RdAld…", Toast.LENGTH_SHORT).show()
                } else {
                    SavedTextDb.get(this).insert(
                        SavedText("Shared Text", sharedText, System.currentTimeMillis())
                    )
                    Toast.makeText(this, "Text added", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }
}
