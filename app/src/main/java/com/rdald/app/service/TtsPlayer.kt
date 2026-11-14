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
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var title: String = "RdAld"
    private var sentences: List<String> = emptyList()
    private var playlist: List<MediaItemData> = emptyList()
    private var index: Int = 0
    private var playWhenReady: Boolean = false
    private var playbackState: Int = Player.STATE_IDLE

    private var speechRate = 1.0f
    private var speechPitch = 1.0f

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingIndex: Int? = null
    private var pendingPlayTitle: String? = null
    private var pendingPlayText: String? = null
    private var synthId: String? = null

    private var mediaPlayer: MediaPlayer? = null
    private var preparedIndex = -1
    private val requestedSynth = HashSet<Int>()
    private var currentVoiceName: String = "default"

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(speechPitch)
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { onSynthDone(id) }
                    override fun onError(id: String?) { onSynthDone(id) }
                })
                pendingIndex?.let { pi ->
                    pendingIndex = null
                    mainHandler.post { speakAt(pi) }
                }
                val pt = pendingPlayText
                if (pt != null) {
                    val tt = pendingPlayTitle ?: "RdAld"
                    pendingPlayText = null
                    pendingPlayTitle = null
                    playText(tt, pt)
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun setTtsSettings(rate: Float, pitch: Float) {
        speechRate = rate
        speechPitch = pitch
        tts?.setSpeechRate(rate)
        tts?.setPitch(pitch)
    }

    fun playText(newTitle: String, text: String) {
        mainHandler.post {
            if (!ttsReady) {
                // TTS engine not initialized yet; start once it is (so the language voice applies).
                pendingPlayTitle = newTitle
                pendingPlayText = text
                return@post
            }
            pendingPlayTitle = null
            pendingPlayText = null
            // Refresh speech settings so changes made in Settings apply to this item.
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            speechRate = prefs.getInt("speech_rate", 100) / 100f
            speechPitch = prefs.getInt("speech_pitch", 100) / 100f
            // Detect the text language and pick a random fitting voice for THIS item.
            val langTag = detectLanguage(text)
            val base = Locale.forLanguageTag(langTag).language.ifBlank { "en" }
            val voice = pickVoice(base)
            if (voice != null) {
                tts?.voice = voice
                currentVoiceName = voice.name
            } else {
                tts?.language = Locale(base)
                currentVoiceName = base
            }
            Log.i(TAG, "detected language=$langTag base=$base voice=$currentVoiceName snippet=${text.take(80).replace("\n", " ")}")
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
            title = newTitle.ifBlank { "RdAld" }
            sentences = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
            index = 0
            requestedSynth.clear()
            playlist = buildPlaylist()
            playbackState = if (sentences.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
            playWhenReady = true
            requestAudioFocus()
            if (sentences.isNotEmpty()) speakAt(0)
            invalidateState()
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
    private fun pickVoice(base: String): android.speech.tts.Voice? {
        val voices = try {
            tts?.voices
        } catch (e: Exception) {
            null
        } ?: run {
            Log.w(TAG, "pickVoice: voices == null")
            return null
        }
        val matching = voices.filter { it.locale.language.equals(base, ignoreCase = true) }
        val langs = voices.map { it.locale.language }.distinct().sorted()
        Log.i(TAG, "pickVoice base=$base total=${voices.size} matching=${matching.size} langs=$langs")
        if (matching.isEmpty()) return null
        val local = matching.filter { !it.isNetworkConnectionRequired }
        val chosen = (local.ifEmpty { matching }).randomOrNull()
        Log.i(TAG, "pickVoice chose=${chosen?.name} locale=${chosen?.locale}")
        return chosen
    }

    private fun buildPlaylist(): List<MediaItemData> {
        if (sentences.isEmpty()) return emptyList()
        return sentences.mapIndexed { i, _ ->
            val md = MediaMetadata.Builder()
                .setTitle(title).setIsPlayable(true).setIsBrowsable(false).build()
            val mi = MediaItem.Builder().setMediaId("s$i").setMediaMetadata(md).build()
            MediaItemData.Builder("s$i")
                .setMediaItem(mi).setMediaMetadata(md)
                .setDurationUs(ITEM_DURATION_US).setIsSeekable(true).build()
        }
    }

    private fun speakAt(i: Int) {
        if (sentences.isEmpty()) {
            playWhenReady = false
            playbackState = Player.STATE_ENDED
            invalidateState()
            return
        }
        index = i.coerceIn(0, sentences.size - 1)
        playbackState = Player.STATE_READY
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
            } else {
                playWhenReady = false
                playbackState = Player.STATE_ENDED
            }
            invalidateState()
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
            builder.setPlaylist(items)
                .setCurrentMediaItemIndex(index.coerceIn(0, items.size - 1))
                .setContentPositionMs(0L)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Log.i(TAG, "setPlayWhenReady=$playWhenReady index=$index state=$playbackState")
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            requestAudioFocus()
            val mp = mediaPlayer
            if (mp != null && preparedIndex == index && !mp.isPlaying) {
                mp.start()
                invalidateState()
            } else if (sentences.isNotEmpty()) {
                speakAt(index)
            }
        } else {
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
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> index + 1
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> index - 1
            else -> mediaItemIndex
        }.coerceIn(0, sentences.size - 1)
        Log.i(TAG, "handleSeek cmd=$seekCommand mii=$mediaItemIndex index=$index target=$target pwr=$playWhenReady")
        index = target
        if (playWhenReady) speakAt(target) else invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReady = false
        mediaPlayer?.pause()
        index = 0
        abandonAudioFocus()
        playbackState = if (sentences.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonAudioFocus()
        return Futures.immediateVoidFuture()
    }
}
