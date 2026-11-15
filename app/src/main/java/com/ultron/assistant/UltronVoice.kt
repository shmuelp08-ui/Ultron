package com.ultron.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * ULTRON uses the configured AssemblyAI cloned voice for all generated replies.
 * Android TTS is used only until the user configures an AssemblyAI voice.
 */
object UltronVoice {
    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val key = UltronSettings.getSecret(context, "assemblyai_api_key")
        val voiceId = UltronSettings.get(context, "assemblyai_voice_id")
        if (!key.isNullOrBlank() && !voiceId.isNullOrBlank()) {
            AssemblyAITTS.speak(context, text, key, voiceId)
            return
        }
        speakAndroid(context, text)
    }

    @Synchronized
    private fun speakAndroid(context: Context, text: String) {
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

    /**
     * True while ULTRON audio is queued or playing. The microphone service uses
     * this gate to avoid transcribing the assistant's own voice as user input.
     */
    @Synchronized
    fun isSpeaking(): Boolean {
        return pending.isNotEmpty() || (engine?.isSpeaking == true) || AssemblyAITTS.isSpeaking()
    }

    @Synchronized
    fun speakSystemFallback(context: Context, text: String) {
        speakAndroid(context, text)
    }

    @Synchronized
    fun stop() {
        engine?.stop()
        AssemblyAITTS.stop()
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
