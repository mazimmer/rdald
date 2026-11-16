package com.rdald.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rdald.app.SavedText
import com.rdald.app.data.SavedTextDb
import java.io.File
import java.util.Locale

/**
 * media3 Player that reads text aloud, ONE MediaItem PER SENTENCE.
 *
 * CRITICAL: each sentence is rendered with TTS synthesizeToFile() and then played with a
 * MediaPlayer in THIS process. Playing the audio ourselves (instead of tts.speak(), which
 * plays in the TTS engine's process) makes the active audio-playback UID our own app's UID,
 * which is what Android's MediaSessionStack requires to pick our session as the
 * "media button session" — and therefore what makes Bluetooth AVRCP / headsets work.
 */
// Common function words used for a lightweight, offline language guess.
private val LANG_STOPWORDS: Map<String, Set<String>> = mapOf(
    "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "den", "mit", "sich", "auf", "für", "von", "dem", "des", "im", "ich", "auch", "werden", "aus", "hat", "dass", "sie", "nach", "wird", "bei", "einer", "um", "sind", "noch", "wie", "einem", "über", "einen", "zum", "war", "haben", "nur", "oder", "aber", "vor", "zur", "bis", "mehr", "durch", "man", "sein", "wurde", "sei", "sowie", "diese", "dieser", "kann", "wir"),
    "en" to setOf("the", "and", "of", "to", "in", "is", "that", "it", "for", "was", "with", "as", "on", "are", "be", "this", "have", "from", "or", "by", "not", "but", "they", "you", "at", "all", "we", "can", "has", "will", "would", "there", "their", "what", "which", "when", "who", "if", "do", "his", "her", "about"),
    "fr" to setOf("le", "la", "les", "et", "de", "des", "du", "un", "une", "est", "que", "qui", "dans", "pour", "pas", "sur", "avec", "ce", "il", "elle", "sont", "au", "aux", "se", "par", "plus", "ne", "en", "nous", "vous", "mais", "comme", "cette"),
    "es" to setOf("el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "es", "por", "con", "para", "no", "se", "del", "al", "lo", "como", "más", "pero", "sus", "ya", "este", "porque", "esta", "entre", "cuando", "muy", "sin", "sobre", "también", "hay", "donde", "nos", "fue", "son"),
    "it" to setOf("il", "la", "le", "gli", "di", "che", "e", "un", "una", "per", "con", "non", "si", "in", "del", "dei", "al", "come", "più", "ma", "anche", "questo", "questa", "sono", "da", "nel", "della", "alla", "gli", "hanno", "ci", "sono"),
    "nl" to setOf("de", "het", "een", "en", "van", "in", "is", "dat", "op", "te", "zijn", "met", "voor", "niet", "aan", "er", "om", "ook", "als", "dan", "maar", "bij", "door", "naar", "uit", "ze", "hij", "zij", "wij", "worden", "niet", "geen"),
    "pt" to setOf("de", "que", "e", "do", "da", "em", "um", "uma", "para", "com", "não", "se", "na", "por", "mais", "as", "os", "como", "mas", "foi", "ao", "ele", "das", "dos", "tem", "seu", "sua", "ou", "ser", "quando", "muito", "há"),
    "sv" to setOf("och", "det", "att", "i", "en", "jag", "hon", "som", "han", "på", "den", "med", "var", "sig", "för", "så", "till", "är", "men", "ett", "om", "hade", "de", "av", "inte", "du", "vi", "kan"),
    "da" to setOf("og", "i", "jeg", "det", "at", "en", "den", "til", "er", "som", "på", "de", "med", "han", "af", "for", "ikke", "der", "var", "men", "et", "har", "om", "vi", "kan", "hun", "du"),
    "no" to setOf("og", "i", "jeg", "det", "at", "en", "et", "den", "til", "er", "som", "på", "de", "med", "han", "av", "ikke", "der", "så", "var", "seg", "men", "har", "om", "vi", "kan", "hun", "du")
)

class TtsPlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    companion object {
        private const val TAG = "TtsPlayer"
        private const val ITEM_DURATION_US = 1_000_000L
        private const val DEFAULT_ENGINE_KEY = "__default__"

        /** The item currently loaded/playing in the (single) player instance, for the UI to follow. */
        @Volatile
        var activeItemId: String? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var title: String = "NaraTTS"
    private var sentences: List<String> = emptyList()
    // Paragraph structure (for per-paragraph navigation): start sentence index of each
    // paragraph, and the paragraph index that each sentence belongs to.
    private var paragraphStarts: List<Int> = emptyList()
    private var paragraphOf: IntArray = IntArray(0)
    private var playlist: List<MediaItemData> = emptyList()
    private var index: Int = 0
    private var playWhenReady: Boolean = false
    private var playbackState: Int = Player.STATE_IDLE

    private var speechRate = 1.0f
    private var speechPitch = 1.0f

    // Active engine instance plus a pool of instances keyed by engine package (voices are
    // engine-specific, so cross-engine playback needs one instance per engine).
    private var tts: TextToSpeech? = null
    private var activeEnginePkg: String? = null
    private val ttsInstances = HashMap<String, TextToSpeech>()
    private val readyEngines = HashSet<String>()
    private var ttsReady = false
    private var pendingIndex: Int? = null

    private val progressListener = object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { onSynthDone(id) }
        override fun onError(id: String?) { onSynthDone(id) }
    }

    private data class PickedVoice(val enginePkg: String, val voice: android.speech.tts.Voice) {
        val key: String get() = "$enginePkg|${voice.name}"
    }
    private var pendingPlayTitle: String? = null
    private var pendingPlayText: String? = null
    private var pendingPlayItemId: String? = null
    private var pendingPlayPercent: Int = 0
    private var pendingPlayAnnounce: Boolean = true
    private var synthId: String? = null

    private var mediaPlayer: MediaPlayer? = null
    private var preparedIndex = -1
    private val requestedSynth = HashSet<Int>()
    private var currentVoiceName: String = "default"

    private var currentItemId: String? = null
    private var artworkUri: android.net.Uri? = null
    private var currentTotalSeconds: Int = 0
    private var currentStartPercent: Int = 0

    private var itemVoice: android.speech.tts.Voice? = null
    private var itemVoiceName: String = "default"
    private var itemEnginePkg: String? = null
    private var itemLangBase: String = "en"

    private var announcePlayer: MediaPlayer? = null
    private var announceSynthId: String? = null
    private var announceFile: File? = null
    private var announceDone: (() -> Unit)? = null
    // Incremented whenever an announcement is aborted; stale start/read callbacks check it.
    private var announceGen: Int = 0

    private var pendingNextItem: SavedText? = null
    private var nextStartRunnable: Runnable? = null

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    init {
        val defaultPkg = com.rdald.app.TtsEngines.resolve(context)
        val pkgs = com.rdald.app.TtsEngines.installed(context).map { it.packageName }.toMutableList()
        if (defaultPkg != null && defaultPkg !in pkgs) pkgs.add(0, defaultPkg)
        if (pkgs.isEmpty()) {
            createEngine(null)
        } else {
            for (p in pkgs) createEngine(p)
            if (defaultPkg != null) makeActive(defaultPkg)
        }
    }

    /** Create (once) a TTS instance bound to [pkg] (null = system default). */
    private fun createEngine(pkg: String?) {
        val key = pkg ?: DEFAULT_ENGINE_KEY
        if (ttsInstances.containsKey(key)) return
        val listener = TextToSpeech.OnInitListener { status -> onEngineInit(key, status) }
        val inst = if (pkg != null) {
            TextToSpeech(context, listener, pkg)
        } else {
            TextToSpeech(context, listener)
        }
        ttsInstances[key] = inst
        if (activeEnginePkg == null) {
            activeEnginePkg = key
            tts = inst
        }
    }

    private fun onEngineInit(key: String, status: Int) {
        mainHandler.post {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed ($key): $status")
                return@post
            }
            readyEngines.add(key)
            val inst = ttsInstances[key] ?: return@post
            try {
                inst.setSpeechRate(speechRate)
                inst.setPitch(speechPitch)
                inst.setOnUtteranceProgressListener(progressListener)
                inst.language = Locale.getDefault()
            } catch (_: Exception) {
            }
            if (key == activeEnginePkg) {
                ttsReady = true
                applyItemVoiceToActive()
                pendingIndex?.let { pi ->
                    pendingIndex = null
                    if (playWhenReady) speakAt(pi)
                }
                val pt = pendingPlayText
                if (pt != null) {
                    val tt = pendingPlayTitle ?: "NaraTTS"
                    val pid = pendingPlayItemId ?: ""
                    val ppct = pendingPlayPercent
                    val pann = pendingPlayAnnounce
                    pendingPlayText = null
                    pendingPlayTitle = null
                    pendingPlayItemId = null
                    playText(tt, pt, pid, ppct, pann)
                }
            }
        }
    }

    /** Point [tts] at the instance for [pkg], creating it if needed. Returns whether it is ready. */
    private fun makeActive(pkg: String?): Boolean {
        val key = pkg ?: DEFAULT_ENGINE_KEY
        if (!ttsInstances.containsKey(key)) createEngine(pkg)
        if (activeEnginePkg != key) {
            activeEnginePkg = key
            tts = ttsInstances[key]
            ttsReady = key in readyEngines
        }
        return key in readyEngines
    }

    /** Re-apply the current item's voice after an engine finishes initializing. */
    private fun applyItemVoiceToActive() {
        val v = itemVoice ?: return
        if (itemEnginePkg != activeEnginePkg) return
        try {
            tts?.voice = v
            currentVoiceName = "${itemEnginePkg ?: DEFAULT_ENGINE_KEY}|$itemVoiceName"
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
        } catch (_: Exception) {
        }
    }

    fun setTtsSettings(rate: Float, pitch: Float) {
        speechRate = rate
        speechPitch = pitch
        tts?.setSpeechRate(rate)
        tts?.setPitch(pitch)
    }

    fun playText(
        newTitle: String,
        text: String,
        itemId: String = "",
        startPercent: Int = 0,
        announceTime: Boolean = true
    ) {
        mainHandler.post {
            if (!ttsReady) {
                pendingPlayTitle = newTitle
                pendingPlayText = text
                pendingPlayItemId = itemId
                pendingPlayPercent = startPercent
                pendingPlayAnnounce = announceTime
                return@post
            }
            pendingPlayTitle = null
            pendingPlayText = null
            // Stop any previous item's audio/announcement so only the new title is heard first.
            abortAnnouncements()
            try {
                mediaPlayer?.pause()
            } catch (_: Exception) {
            }
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            speechRate = prefs.getInt("speech_rate", 100) / 100f
            speechPitch = prefs.getInt("speech_pitch", 100) / 100f
            title = newTitle.ifBlank { "NaraTTS" }
            val parts = buildParagraphs(text)
            sentences = parts.first
            paragraphStarts = parts.second
            paragraphOf = parts.third
            currentItemId = itemId.ifBlank { null }
            activeItemId = currentItemId
            // Feed the article favicon to the media notification (shown as the large icon).
            artworkUri = try {
                currentItemId?.let { SavedTextDb.get(context).getById(it)?.iconPath }
                    ?.let { java.io.File(it) }
                    ?.takeIf { it.exists() }
                    ?.let { android.net.Uri.fromFile(it) }
            } catch (_: Exception) {
                null
            }
            currentStartPercent = startPercent.coerceIn(0, 100)
            requestedSynth.clear()
            currentTotalSeconds = estimateSeconds(wordCount(text))
            playlist = buildPlaylist()
            playbackState = if (sentences.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
            playWhenReady = true
            requestAudioFocus()
            val startIndex = if (currentStartPercent in 1..99 && sentences.isNotEmpty())
                (currentStartPercent * sentences.size / 100).coerceIn(0, sentences.size - 1) else 0
            index = startIndex
            persistProgress()

            val langTag = detectLanguage(text)
            itemLangBase = Locale.forLanguageTag(langTag).language.ifBlank { "en" }
            val picked = pickVoice(itemLangBase)
            itemVoice = picked?.voice
            itemEnginePkg = picked?.enginePkg
            itemVoiceName = picked?.voice?.name ?: itemLangBase
            if (picked != null) makeActive(picked.enginePkg)
            Log.i(TAG, "detected language=$langTag base=$itemLangBase voice=$itemVoiceName engine=$itemEnginePkg snippet=${text.take(60).replace("\n", " ")}")

            if (sentences.isEmpty()) {
                playWhenReady = false
                playbackState = Player.STATE_ENDED
                invalidateState()
                return@post
            }

            val remainingSec = currentTotalSeconds * (100 - currentStartPercent) / 100
            val thresholdMin = prefs.getInt("announce_threshold_min", 5).coerceAtLeast(0)
            val shouldAnnounce = announceTime && remainingSec > thresholdMin * 60
            val myGen = announceGen
            val afterTitle = {
                if (myGen == announceGen) {
                    if (shouldAnnounce && playWhenReady) {
                        val mins = (remainingSec + 59) / 60
                        val msg = if (currentStartPercent > 0) "The remaining item time is $mins minutes."
                        else "The item time is $mins minutes."
                        announce(msg) { if (myGen == announceGen) startSpeaking(startIndex) }
                    } else {
                        startSpeaking(startIndex)
                    }
                }
            }
            // Announce the item title first (in the item's own voice), then continue.
            announce(title, useItemVoice = true) { if (myGen == announceGen) afterTitle() }
            invalidateState()
        }
    }

    private fun applyItemVoice() {
        makeActive(itemEnginePkg)
        val v = itemVoice
        try {
            if (v != null) {
                tts?.voice = v
                currentVoiceName = "${itemEnginePkg ?: DEFAULT_ENGINE_KEY}|$itemVoiceName"
            } else {
                tts?.language = Locale(itemLangBase)
                currentVoiceName = itemLangBase
            }
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
        } catch (_: Exception) {
        }
    }

    private fun startSpeaking(start: Int) {
        applyItemVoice()
        if (playWhenReady) speakAt(start)
    }

    private fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun estimateSeconds(words: Int): Int {
        val wpm = 180.0 * speechRate.coerceAtLeast(0.5f)
        return Math.max(1, (words / wpm * 60.0).toInt())
    }

    /** Split into paragraphs -> sentences, keeping paragraph boundaries for navigation. */
    private fun buildParagraphs(text: String): Triple<List<String>, List<Int>, IntArray> {
        val paragraphs = text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotBlank() }
        val outSentences = ArrayList<String>()
        val starts = ArrayList<Int>()
        val paraOf = ArrayList<Int>()
        val seenParas = HashSet<String>()
        var pIndex = 0
        for (para in paragraphs) {
            val paraKey = normalizeText(para)
            // Skip exact duplicate paragraphs (whitespace/case-insensitive).
            if (paraKey.isEmpty() || !seenParas.add(paraKey)) continue
            val sents = para.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
            if (sents.isEmpty()) continue
            val kept = ArrayList<String>()
            val seenSents = HashSet<String>()
            for (s in sents) {
                val key = normalizeText(s)
                if (key.isEmpty() || !seenSents.add(key)) continue
                kept.add(s)
            }
            if (kept.isEmpty()) continue
            starts.add(outSentences.size)
            for (s in kept) {
                outSentences.add(s)
                paraOf.add(pIndex)
            }
            pIndex++
        }
        if (outSentences.isEmpty() && text.isNotBlank()) {
            outSentences.add(text.trim())
            paraOf.add(0)
            starts.add(0)
        }
        return Triple(outSentences, starts, paraOf.toIntArray())
    }

    private fun normalizeText(s: String): String =
        s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun paragraphIndexOfSentence(i: Int): Int {
        if (paragraphOf.isEmpty()) return 0
        return paragraphOf[i.coerceIn(0, paragraphOf.size - 1)]
    }

    private fun forwardParagraphTarget(): Int {
        if (paragraphStarts.isEmpty()) return (index + 1).coerceAtMost((sentences.size - 1).coerceAtLeast(0))
        val p = paragraphIndexOfSentence(index)
        val next = p + 1
        return if (next < paragraphStarts.size) paragraphStarts[next] else (sentences.size - 1).coerceAtLeast(0)
    }

    private fun backParagraphTarget(): Int {
        if (paragraphStarts.isEmpty()) return (index - 1).coerceAtLeast(0)
        val p = paragraphIndexOfSentence(index)
        val start = paragraphStarts[p]
        return if (index > start) start else if (p > 0) paragraphStarts[p - 1] else 0
    }

    private fun persistProgress() {
        val id = currentItemId ?: return
        if (sentences.isEmpty()) return
        val pct = (index * 100 / sentences.size).coerceIn(0, 100)
        currentStartPercent = pct
        try {
            SavedTextDb.get(context).setProgress(id, pct)
        } catch (e: Exception) {
            Log.w(TAG, "setProgress failed: ${e.message}")
        }
    }

    fun onMediaKey(code: Int) {
        Log.i(TAG, "onMediaKey code=$code")
        mainHandler.post {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> setPlayWhenReady(true)
                KeyEvent.KEYCODE_MEDIA_PAUSE -> setPlayWhenReady(false)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                    setPlayWhenReady(!playWhenReady)
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                    seekToNextMediaItem()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND ->
                    seekToPreviousMediaItem()
                KeyEvent.KEYCODE_MEDIA_STOP -> handleStop()
            }
        }
    }

    private fun fileFor(sentence: String): File {
        // Include rate/pitch in the cache key so a changed speed re-synthesizes instead of
        // replaying a file rendered at the old speed.
        val rt = (speechRate * 100).toInt()
        val pt = (speechPitch * 100).toInt()
        val vt = currentVoiceName.replace(Regex("[^A-Za-z0-9]"), "_")
        return File(context.cacheDir, "rdald_" + Math.abs(sentence.hashCode()) + "_${rt}_${pt}_${vt}.wav")
    }

    /** Detect the language of [text] (BCP-47 tag) using the platform classifier. */
    private fun detectLanguage(text: String): String {
        val sample = text.take(2000)
        // 1) Stopword heuristic — reliable for the languages we support.
        val words = sample.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 2 }
        if (words.size >= 8) {
            var bestLang = ""
            var bestScore = 0
            for ((lang, set) in LANG_STOPWORDS) {
                var s = 0
                for (w in words) if (set.contains(w)) s++
                if (s > bestScore) {
                    bestScore = s
                    bestLang = lang
                }
            }
            val ratio = bestScore.toDouble() / words.size
            if (bestLang.isNotEmpty() && bestScore >= 3 && ratio >= 0.04) {
                Log.i(TAG, "detect heuristic lang=$bestLang score=$bestScore ratio=$ratio")
                return bestLang
            }
        }
        // 2) Platform classifier fallback.
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sample.isNotBlank()) {
                val tcm = context.getSystemService(
                    android.view.textclassifier.TextClassificationManager::class.java
                )
                val req = android.view.textclassifier.TextLanguage.Request.Builder(sample).build()
                val result = tcm?.textClassifier?.detectLanguage(req)
                val lang = result?.id
                if (!lang.isNullOrBlank()) lang else "en"
            } else "en"
        } catch (e: Exception) {
            "en"
        }
    }

    /** Pick a random installed voice whose language matches [base] (e.g. "en", "de"). */
    /** Pick a random *selected* voice for [base] across ALL ready engines. */
    private fun pickVoice(base: String, avoidKey: String? = null): PickedVoice? {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val deselected = prefs.getStringSet("deselected_voices", emptySet()) ?: emptySet()
        val candidates = ArrayList<PickedVoice>()
        for ((pkg, inst) in ttsInstances) {
            if (pkg !in readyEngines) continue
            val vs = try {
                inst.voices
            } catch (_: Exception) {
                null
            } ?: continue
            for (v in vs) {
                if (v.locale.language.equals(base, ignoreCase = true)) {
                    candidates.add(PickedVoice(pkg, v))
                }
            }
        }
        if (candidates.isEmpty()) {
            // No ready engine advertises this language yet; fall back to the active instance.
            val vs = try {
                tts?.voices
            } catch (_: Exception) {
                null
            } ?: run {
                Log.w(TAG, "pickVoice: no voices for base=$base")
                return null
            }
            val fb = vs.filter { it.locale.language.equals(base, ignoreCase = true) }
            val chosen = fb.randomOrNull() ?: return null
            return PickedVoice(activeEnginePkg ?: DEFAULT_ENGINE_KEY, chosen)
        }
        // Selections are stored as keys "enginePkg|voiceName" so the same voice name from two
        // different engines is tracked independently. New engines' voices default to selected.
        val notCurrent = if (avoidKey == null) candidates else candidates.filter { it.key != avoidKey }
        val pool = if (notCurrent.isNotEmpty()) notCurrent else candidates
        val allowed = pool.filter { it.key !in deselected }.ifEmpty {
            // User deselected every voice for this language: still speak, using them anyway.
            pool
        }
        val local = allowed.filter { !it.voice.isNetworkConnectionRequired }.ifEmpty { allowed }
        val chosen = local.randomOrNull()
        Log.i(
            TAG,
            "pickVoice base=$base engines=${ttsInstances.size} candidates=${candidates.size} " +
                "allowed=${allowed.size} chose=${chosen?.voice?.name}@${chosen?.enginePkg}"
        )
        return chosen
    }

    /**
     * Deselect the voice currently used for this item, then randomly pick another fitting,
     * still-selected voice (possibly from another engine) and re-read the current sentence.
     */
    fun deselectCurrentVoiceAndSwitch() {
        mainHandler.post {
            if (sentences.isEmpty()) return@post
            val curPkg = itemEnginePkg
            val curName = itemVoiceName
            val curKey = if (curPkg != null && curName.isNotBlank() && curName != itemLangBase) {
                "$curPkg|$curName"
            } else {
                null
            }
            if (curKey != null) {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val ds = HashSet(prefs.getStringSet("deselected_voices", emptySet()) ?: emptySet())
                ds.add(curKey)
                prefs.edit().putStringSet("deselected_voices", ds).apply()
            }
            val next = pickVoice(itemLangBase, avoidKey = curKey)
            if (next == null) {
                Log.w(TAG, "deselectCurrentVoiceAndSwitch: no alternative voice")
                return@post
            }
            itemVoice = next.voice
            itemEnginePkg = next.enginePkg
            itemVoiceName = next.voice.name
            makeActive(next.enginePkg)
            applyItemVoice()
            requestedSynth.clear()
            // Re-read the current sentence from its start with the new voice.
            if (playWhenReady) speakAt(index) else invalidateState()
            Log.i(TAG, "deselectCurrentVoiceAndSwitch -> ${next.voice.name}@${next.enginePkg}")
        }
    }

    private fun buildPlaylist(): List<MediaItemData> {
        if (sentences.isEmpty()) return emptyList()
        return sentences.mapIndexed { i, _ ->
            val mdBuilder = MediaMetadata.Builder()
                .setTitle(title).setIsPlayable(true).setIsBrowsable(false)
            artworkUri?.let { mdBuilder.setArtworkUri(it) }
            val md = mdBuilder.build()
            val mi = MediaItem.Builder().setMediaId("s$i").setMediaMetadata(md).build()
            MediaItemData.Builder("s$i")
                .setMediaItem(mi).setMediaMetadata(md)
                .setDurationUs(totalDurationUs()).setIsSeekable(true).build()
        }
    }

    private fun totalDurationUs(): Long = currentTotalSeconds.coerceAtLeast(1) * 1_000_000L

    private fun speakAt(i: Int) {
        if (sentences.isEmpty()) {
            playWhenReady = false
            playbackState = Player.STATE_ENDED
            invalidateState()
            return
        }
        index = i.coerceIn(0, sentences.size - 1)
        playbackState = Player.STATE_READY
        persistProgress()
        val sentence = sentences[index]
        Log.i(TAG, "speakAt index=$index pwr=$playWhenReady ttsReady=$ttsReady")
        val f = fileFor(sentence)
        if (f.exists() && f.length() > 0) {
            playFile(f)
            ensureSynth(index + 1)
            return
        }
        if (!ttsReady) {
            pendingIndex = index
            invalidateState()
            return
        }
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(speechPitch)
        val uid = java.util.UUID.randomUUID().toString()
        synthId = uid
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
        }
        val r = tts?.synthesizeToFile(sentence, params, f, uid)
        if (r != TextToSpeech.SUCCESS) Log.e(TAG, "synthesizeToFile failed: $r")
        ensureSynth(index + 1)
        invalidateState()
    }

    /** Pre-render the next sentence in the background so playback can continue seamlessly. */
    private fun ensureSynth(i: Int) {
        if (i < 0 || i >= sentences.size || !ttsReady) return
        if (requestedSynth.contains(i)) return
        val f = fileFor(sentences[i])
        if (f.exists() && f.length() > 0) return
        requestedSynth.add(i)
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(speechPitch)
        val uid = "pre_" + java.util.UUID.randomUUID().toString()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
        }
        tts?.synthesizeToFile(sentences[i], params, f, uid)
    }

    private fun onSynthDone(id: String?) {
        mainHandler.post {
            if (id != null && id == announceSynthId) {
                announceSynthId = null
                val f = announceFile
                val cb = announceDone
                announceFile = null
                announceDone = null
                if (f != null && f.exists() && f.length() > 0) playAnnouncement(f, cb) else cb?.invoke()
                return@post
            }
            if (id != synthId) return@post
            if (!playWhenReady) return@post
            if (sentences.isEmpty()) return@post
            val f = fileFor(sentences[index.coerceIn(0, sentences.size - 1)])
            if (f.exists() && f.length() > 0) playFile(f) else onSentenceComplete()
        }
    }

    private fun playFile(f: File) {
        try {
            if (mediaPlayer == null) mediaPlayer = MediaPlayer()
            val mp = mediaPlayer!!
            mp.reset()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { onSentenceComplete() }
            mp.setOnErrorListener { _, _, _ -> onSentenceComplete(); true }
            mp.prepare()
            preparedIndex = index
            playbackState = Player.STATE_READY
            if (playWhenReady) mp.start()
            invalidateState()
        } catch (e: Exception) {
            Log.e(TAG, "playFile failed", e)
            onSentenceComplete()
        }
    }

    private fun onSentenceComplete() {
        mainHandler.post {
            if (!playWhenReady) return@post
            if (index + 1 < sentences.size) {
                index++
                speakAt(index)
                invalidateState()
            } else {
                onItemFinished()
            }
        }
    }

    /** Current item finished: archive it, then continue with the next inbox item (or announce end). */
    /** Stop the sentence audio immediately (used when jumping to the very end). */
    private fun stopMediaPlayback() {
        try {
            mediaPlayer?.reset()
        } catch (_: Exception) {
        }
        preparedIndex = -1
    }

    /**
     * Jump straight to the end of the current item: stop the current voice, then run the normal
     * end-of-item flow (persist 100%, archive, announce/continue).
     */
    fun finishCurrentItem() {
        mainHandler.post {
            if (sentences.isEmpty()) return@post
            abortAnnouncements()
            stopMediaPlayback()
            index = (sentences.size - 1).coerceAtLeast(0)
            onItemFinished()
        }
    }

    private fun onItemFinished() {
        val id = currentItemId
        if (id != null) {
            try {
                SavedTextDb.get(context).setProgress(id, 100)
                SavedTextDb.get(context).setArchived(listOf(id), true)
            } catch (e: Exception) {
                Log.w(TAG, "finish persist failed: ${e.message}")
            }
        }
        val next = try {
            SavedTextDb.get(context).getAll(false).firstOrNull { it.id != id }
        } catch (e: Exception) {
            null
        }
        if (next != null) {
            pendingNextItem = next
            announce("Continuing with the next item.") {
                if (playWhenReady) {
                    val r = Runnable { startPendingNext() }
                    nextStartRunnable = r
                    mainHandler.postDelayed(r, 3000)
                }
            }
            playbackState = Player.STATE_READY
        } else {
            announce("No more items in the inbox.") {
                playWhenReady = false
                playbackState = Player.STATE_ENDED
                invalidateState()
            }
            playbackState = Player.STATE_READY
        }
        invalidateState()
    }

    private fun startPendingNext() {
        val n = pendingNextItem ?: return
        pendingNextItem = null
        nextStartRunnable = null
        playText(n.title, n.content, n.id, 0, announceTime = false)
    }

    /** Speak [text] in an explicit English voice (for app announcements), then run [onDone]. */
    /**
     * Abort any in-progress title/time announcement (and any pending callback that would
     * start reading afterwards). Used when the user seeks, taps a paragraph, or skips so that
     * playback jumps immediately instead of finishing the announcement first.
     */
    private fun abortAnnouncements() {
        announceGen++
        announceSynthId = null
        announceDone = null
        announceFile = null
        try {
            announcePlayer?.reset()
        } catch (_: Exception) {
        }
    }

    private fun announce(text: String, useItemVoice: Boolean = false, onDone: () -> Unit) {
        if (text.isBlank() || !ttsReady) {
            onDone()
            return
        }
        if (useItemVoice) {
            applyItemVoice()
        } else {
            val en = pickVoice("en")
            if (en != null) {
                makeActive(en.enginePkg)
                try {
                    tts?.voice = en.voice
                } catch (_: Exception) {
                }
                currentVoiceName = en.key
            } else {
                tts?.language = Locale.ENGLISH
                currentVoiceName = "en"
            }
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)
        }
        Log.i(TAG, "announce(useItemVoice=$useItemVoice): $text")
        val vt = currentVoiceName.replace(Regex("[^A-Za-z0-9]"), "_")
        val f = File(context.cacheDir, "ann_" + Math.abs(text.hashCode()) + "_" + vt + ".wav")
        if (f.exists() && f.length() > 0) {
            playAnnouncement(f, onDone)
            return
        }
        announceFile = f
        announceDone = onDone
        val uid = "ann_" + java.util.UUID.randomUUID().toString()
        announceSynthId = uid
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid) }
        val r = tts?.synthesizeToFile(text, params, f, uid)
        if (r != TextToSpeech.SUCCESS) {
            announceSynthId = null
            announceFile = null
            val cb = announceDone
            announceDone = null
            cb?.invoke()
        }
    }

    private fun playAnnouncement(f: File, onDone: (() -> Unit)?) {
        try {
            if (announcePlayer == null) announcePlayer = MediaPlayer()
            val mp = announcePlayer!!
            mp.reset()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { onDone?.invoke() }
            mp.setOnErrorListener { _, _, _ -> onDone?.invoke(); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "playAnnouncement failed", e)
            onDone?.invoke()
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) mainHandler.post { setPlayWhenReady(false) }
            }
            .build()
        focusRequest = req
        hasAudioFocus = audioManager?.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
    }

    override fun getState(): State {
        val items = playlist.ifEmpty { buildPlaylist() }
        val builder = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
        if (items.isNotEmpty()) {
            val idx = index.coerceIn(0, items.size - 1)
            // Report position as a fraction of the whole item so the notification progress
            // bar reflects item-level progress rather than a single sentence.
            val totalMs = currentTotalSeconds.coerceAtLeast(1) * 1000L
            val pos = totalMs * idx / items.size
            builder.setPlaylist(items)
                .setCurrentMediaItemIndex(idx)
                .setContentPositionMs(pos)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Log.i(TAG, "setPlayWhenReady=$playWhenReady index=$index state=$playbackState")
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            requestAudioFocus()
            // Resume a "continue with the next item" that was paused during the announcement.
            if (pendingNextItem != null) {
                startPendingNext()
                invalidateState()
                return Futures.immediateVoidFuture()
            }
            val mp = mediaPlayer
            if (mp != null && preparedIndex == index && !mp.isPlaying) {
                mp.start()
                invalidateState()
            } else if (sentences.isNotEmpty()) {
                speakAt(index)
            }
        } else {
            nextStartRunnable?.let { mainHandler.removeCallbacks(it) }
            nextStartRunnable = null
            try {
                if (announcePlayer?.isPlaying == true) announcePlayer?.pause()
            } catch (_: Exception) {
            }
            mediaPlayer?.pause()
            abandonAudioFocus()
            invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (sentences.isNotEmpty() && playbackState == Player.STATE_IDLE) playbackState = Player.STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (sentences.isEmpty()) return Futures.immediateVoidFuture()
        // A user seek/tap/jump should abort a still-playing title or time announcement
        // instead of waiting for it (and its follow-up read) to finish.
        abortAnnouncements()
        val isForward = seekCommand == Player.COMMAND_SEEK_FORWARD ||
            seekCommand == Player.COMMAND_SEEK_TO_NEXT ||
            seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        if (isForward) {
            val atLastParagraph = paragraphStarts.isEmpty() ||
                paragraphIndexOfSentence(index) >= paragraphStarts.size - 1
            if (atLastParagraph) {
                // Already on the last paragraph (or a single-paragraph item): jump to the very
                // end and let the normal end-of-item flow continue (archive + announce next).
                index = (sentences.size - 1).coerceAtLeast(0)
                stopMediaPlayback()
                if (playWhenReady) onItemFinished() else invalidateState()
                return Futures.immediateVoidFuture()
            }
        }
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> forwardParagraphTarget()
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> backParagraphTarget()
            else -> mediaItemIndex
        }.coerceIn(0, sentences.size - 1)
        Log.i(TAG, "handleSeek cmd=$seekCommand mii=$mediaItemIndex index=$index target=$target pwr=$playWhenReady")
        index = target
        if (playWhenReady) speakAt(target) else invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReady = false
        nextStartRunnable?.let { mainHandler.removeCallbacks(it) }
        nextStartRunnable = null
        pendingNextItem = null
        try {
            announcePlayer?.pause()
        } catch (_: Exception) {
        }
        mediaPlayer?.pause()
        index = 0
        abandonAudioFocus()
        playbackState = if (sentences.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        nextStartRunnable?.let { mainHandler.removeCallbacks(it) }
        nextStartRunnable = null
        pendingNextItem = null
        mediaPlayer?.release()
        mediaPlayer = null
        announcePlayer?.release()
        announcePlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonAudioFocus()
        return Futures.immediateVoidFuture()
    }
}
