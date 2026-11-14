package com.rdald.app.service

import android.app.Service
import android.content.Intent
import android.util.Log
import com.rdald.app.SavedText
import com.rdald.app.data.SavedTextDb
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a URL in the background, extracts the main article text in "reader mode"
 * via Readability4J (+ Jsoup for paragraph structure), and stores it in the database.
 * Posts ACTION_TEXTS_CHANGED when done so the list can refresh.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_DOWNLOAD = "com.rdald.app.action.DOWNLOAD"
        const val EXTRA_URL = "com.rdald.app.extra.URL"
        const val ACTION_TEXTS_CHANGED = "com.rdald.app.action.TEXTS_CHANGED"
        const val EXTRA_ERROR = "com.rdald.app.extra.ERROR"

        fun isUrl(s: String): Boolean {
            val t = s.trim()
            return (t.startsWith("http://") || t.startsWith("https://")) && !t.contains(' ') && t.contains('.')
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread {
            try {
                val (title, text) = fetchArticle(url)
                if (text.isBlank()) throw IllegalStateException("No text found")
                SavedTextDb.get(this).insert(
                    SavedText(title = title, content = text, createdAt = System.currentTimeMillis())
                )
                sendBroadcast(Intent(ACTION_TEXTS_CHANGED).setPackage(packageName))
            } catch (e: Exception) {
                Log.e(TAG, "download failed: ${e.message}", e)
                sendBroadcast(
                    Intent(ACTION_TEXTS_CHANGED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_ERROR, e.message ?: "Download failed")
                )
            } finally {
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun fetchArticle(url: String): Pair<String, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
        val html = conn.inputStream.bufferedReader().use { it.readText() }

        val article = Readability4J(url, html).parse()
        val title = article.title?.trim()?.takeIf { it.isNotEmpty() } ?: url

        // Prefer the cleaned HTML content and reconstruct paragraphs with Jsoup so the
        // read-aloud text keeps paragraph breaks (getTextContent flattens whitespace).
        val contentHtml = article.content
        val sb = StringBuilder()
        if (!contentHtml.isNullOrBlank()) {
            val doc = Jsoup.parse(contentHtml)
            doc.select("h1,h2,h3,h4,p,li,blockquote").forEach { el ->
                val t = el.text().trim()
                if (t.isNotEmpty()) sb.append(t).append("\n\n")
            }
        }
        var text = sb.toString().trim()
        if (text.isEmpty()) text = article.textContent?.trim().orEmpty()
        if (text.isEmpty()) {
            text = Jsoup.parse(html).body()?.text()?.trim().orEmpty()
        }
        return title to text
    }

    override fun onBind(intent: Intent?) = null
}
