package com.qingsongreader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Read-aloud engine. Uses the device TTS engine: no account, no quota, no
 * server round-trip, so the read-aloud feature is always available.
 */
class TtsController(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    var onDone: (() -> Unit)? = null
    var speaking: Boolean = false
        private set

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }

            override fun onDone(utteranceId: String?) {
                speaking = false
                onDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speaking = false
                onDone?.invoke()
            }
        })
    }

    fun speak(text: String, rate: Float, pitch: Float, utteranceId: String = "or-tts") {
        if (!ready) return
        val tts = tts ?: return
        tts.setSpeechRate(rate.coerceIn(0.5f, 3.0f))
        tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
        val chunk = text.take(3500)
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        speaking = true
        tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /** Reads the next chunk; used to continue past the 3500 character limit. */
    fun speakNext(text: String, rate: Float, pitch: Float, utteranceId: String) {
        speak(text, rate, pitch, utteranceId)
    }

    fun stop() {
        speaking = false
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
