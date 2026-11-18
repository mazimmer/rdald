package com.rdald.app.service
import com.rdald.app.R
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import com.rdald.app.SavedText
import com.rdald.app.data.SavedTextDb
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Downloads a URL in the background, extracts the main article text in "reader mode"
 * via Readability4J (+ Jsoup for paragraph structure), and stores it in the database.
 * Posts ACTION_TEXTS_CHANGED when done so the list can refresh.
 */
class DownloadService : Service() {

    private data class ArticleResult(
        val title: String,
        val text: String,
        val baseUrl: String,
        val html: String
    )

    companion object {
        private const val TAG = "DownloadService"
        private const val USER_AGENT =
            "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val MAX_ICON_BYTES = 512 * 1024
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
                val res = fetchArticle(url)
                if (res.text.isBlank()) throw IllegalStateException(getString(R.string.error_no_text))
                val id = UUID.randomUUID().toString()
                val iconPath = saveFavicon(res, id)
                SavedTextDb.get(this).insert(
                    SavedText(
                        title = res.title,
                        content = res.text,
                        createdAt = System.currentTimeMillis(),
                        id = id,
                        iconPath = iconPath,
                        sourceUrl = res.baseUrl
                    )
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

    private fun fetchArticle(url: String): ArticleResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        // Final URL after redirects, used to resolve the favicon.
        val baseUrl = try {
            conn.url.toString()
        } catch (_: Exception) {
            url
        }

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
        // Remove URLs and junk tokens, then strip duplicate blocks/sentences before storing.
        text = cleanExtractedText(text)
        text = dedupeText(text)
        return ArticleResult(title, text, baseUrl, html)
    }

    private val urlRegex = Regex("(?i)\\b(?:https?://|www\\.)\\S+")

    /**
     * Remove URLs, bracketed citations and "garbage" tokens (pure symbols, long ids/hashes, and
     * keyboard-mash words), and drop paragraphs that are mostly symbols.
     */
    private fun cleanExtractedText(raw: String): String {
        val out = ArrayList<String>()
        for (para in raw.split(Regex("\\n\\s*\\n"))) {
            var p = urlRegex.replace(para, " ")
            // Citation-style references like [1] / [12].
            p = p.replace(Regex("\\[\\s*\\d{1,3}\\s*]"), " ")
            val kept = p.split(Regex("\\s+")).filter { it.isNotBlank() && !isJunkToken(it) }
            val line = kept.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            if (line.isBlank() || isMostlySymbols(line)) continue
            out.add(line)
        }
        return out.joinToString("\n\n")
    }

    private fun isJunkToken(t: String): Boolean {
        var letters = 0
        var digits = 0
        var other = 0
        for (c in t) {
            when {
                c.isLetter() -> letters++
                c.isDigit() -> digits++
                else -> other++
            }
        }
        // Pure punctuation/symbols, or tokens that are mostly symbols.
        if (letters == 0 && digits == 0) return true
        if (other > letters + digits) return true
        // Long number-only tokens (ids, counters).
        if (letters == 0 && digits >= 6) return true
        // Long mixed letter+digit blobs (tracking ids, base64-ish).
        if (t.length >= 20 && letters > 0 && digits > 0 && letters.toDouble() / t.length < 0.7) {
            return true
        }
        // Long ASCII-only "words" with almost no vowels (hash/keyboard mash); other scripts safe.
        if (t.length >= 12 && t.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            val vowels = t.count { it.lowercaseChar() in "aeiouy" }
            if (vowels.toDouble() / t.length < 0.15) return true
        }
        return false
    }

    private fun isMostlySymbols(line: String): Boolean {
        if (line.length < 8) return false
        var letters = 0
        for (c in line) if (c.isLetter()) letters++
        return letters.toDouble() / line.length < 0.25
    }

    private fun normalizeText(s: String): String =
        s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** Remove exact duplicate paragraphs (global) and duplicate sentences within a paragraph. */
    private fun dedupeText(raw: String): String {
        val paras = raw.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
        val seenParas = HashSet<String>()
        val out = ArrayList<String>()
        for (p in paras) {
            val sents = p.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
            val seenSents = HashSet<String>()
            val kept = ArrayList<String>()
            for (s in sents) {
                val k = normalizeText(s)
                if (k.isEmpty() || !seenSents.add(k)) continue
                kept.add(s)
            }
            val para = kept.joinToString(" ").trim()
            val pk = normalizeText(para)
            if (pk.isNotEmpty() && seenParas.add(pk)) out.add(para)
        }
        return out.joinToString("\n\n")
    }

    /**
     * Find the best favicon: declared <link rel="...icon..."> entries first, then the
     * conventional /favicon.ico. Saves the first candidate that downloads and decodes as
     * a valid image, returning its file path (or null).
     */
    private fun saveFavicon(res: ArticleResult, id: String): String? {
        val candidates = LinkedHashSet<String>()
        try {
            val doc = Jsoup.parse(res.html, res.baseUrl)
            doc.select("link[href]").forEach { el ->
                if (el.attr("rel").lowercase().contains("icon")) {
                    val href = el.attr("href").trim()
                    val abs = el.absUrl("href").ifBlank {
                        if (href.startsWith("//")) "https:$href" else ""
                    }
                    if (abs.startsWith("http")) candidates.add(abs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "favicon parse failed: ${e.message}")
        }
        try {
            val u = URL(res.baseUrl)
            val origin = "${u.protocol}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
            candidates.add("$origin/favicon.ico")
            candidates.add("$origin/apple-touch-icon.png")
        } catch (_: Exception) {
        }

        for (iconUrl in candidates) {
            val bytes = downloadBytes(iconUrl) ?: continue
            if (!isValidImage(bytes)) continue
            return try {
                val dir = File(filesDir, "favicons").apply { mkdirs() }
                val f = File(dir, "fav_$id.img")
                f.writeBytes(bytes)
                f.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "favicon save failed: ${e.message}")
                null
            }
        }
        return null
    }

    private fun downloadBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
            }
            conn = c
            if (c.responseCode != HttpURLConnection.HTTP_OK) return null
            if (c.contentLength > MAX_ICON_BYTES) return null
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ICON_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.d(TAG, "favicon fetch failed ($url): ${e.message}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun isValidImage(bytes: ByteArray): Boolean {
        if (bytes.size < 32) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    override fun onBind(intent: Intent?) = null
}
