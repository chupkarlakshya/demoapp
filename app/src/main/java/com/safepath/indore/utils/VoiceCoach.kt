package com.safepath.indore.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Thin wrapper around Android TTS for navigation prompts.
 *
 * Two states: enabled or muted. When muted, [speak] is a no-op so the rest
 * of the app can call it freely without checking the flag.
 */
class VoiceCoach(context: Context) {

    private lateinit var tts: TextToSpeech
    private var ready = false
    var enabled: Boolean = true

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(1.0f)
                ready = true
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) = Unit
        })
    }

    /** Speaks immediately, replacing whatever was queued. No-op when muted. */
    fun speak(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "safepath-${System.currentTimeMillis()}")
    }

    /** Adds to the speech queue; useful for back-to-back prompts. */
    fun queue(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "safepath-${System.currentTimeMillis()}")
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (_: Exception) { /* ignore */ }
    }
}
