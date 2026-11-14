package com.rdald.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
    private var synthId: String? = null

    private var mediaPlayer: MediaPlayer? = null
    private var preparedIndex = -1
    private val requestedSynth = HashSet<Int>()

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

    private fun fileFor(sentence: String): File =
        File(context.cacheDir, "rdald_" + Math.abs(sentence.hashCode()) + ".wav")

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
