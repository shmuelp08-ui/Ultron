package com.ultron.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Uses Android's built-in text-to-speech engine. No external voice API or key required.
 */
object UltronVoice {
    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val fishKey = UltronSettings.get(context, "fish_api_key")
        val fishVoiceId = UltronSettings.get(context, "fish_voice_id")
        if (!fishKey.isNullOrBlank() && !fishVoiceId.isNullOrBlank() && UltronSettings.get(context, "voice_mode") == "fish") {
            FishVoice.speak(context, text, fishKey, fishVoiceId)
            return
        }
        val tts = engine
        if (tts == null) {
            pending.addLast(text)
            engine = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    val current = engine ?: return@synchronized
                    if (status == TextToSpeech.SUCCESS) {
                        val result = current.setLanguage(Locale.US)
                        ready = result != TextToSpeech.LANG_MISSING_DATA &&
                            result != TextToSpeech.LANG_NOT_SUPPORTED
                        current.setSpeechRate(0.94f)
                        current.setPitch(0.78f)
                        if (ready) while (pending.isNotEmpty()) {
                            current.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, "ultron-" + System.nanoTime())
                        } else pending.clear()
                    } else pending.clear()
                }
            }
        } else if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "ultron-" + System.nanoTime())
        } else pending.addLast(text)
    }

    @Synchronized
    fun speakSystemFallback(context: Context, text: String) {
        val tts = engine
        if (tts != null && ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "ultron-fallback-" + System.nanoTime()) else speak(context, text)
    }

    @Synchronized
    fun stop() {
        engine?.stop()
    }

    @Synchronized
    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
        pending.clear()
    }
}
