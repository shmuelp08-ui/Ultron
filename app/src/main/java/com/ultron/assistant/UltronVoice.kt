package com.ultron.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Device-native speech output. This gives ULTRON spoken feedback immediately;
 * a remote voice provider can be added later without changing command handling.
 */
object UltronVoice {
    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val tts = engine
        if (tts == null) {
            pending.addLast(text)
            engine = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    val current = engine ?: return@synchronized
                    if (status == TextToSpeech.SUCCESS) {
                        val languageResult = current.setLanguage(Locale.US)
                        ready = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                        current.setSpeechRate(0.94f)
                        current.setPitch(0.78f)
                        if (ready) {
                            while (pending.isNotEmpty()) {
                                current.speak(
                                    pending.removeFirst(),
                                    TextToSpeech.QUEUE_ADD,
                                    null,
                                    "ultron-" + System.nanoTime()
                                )
                            }
                        } else {
                            pending.clear()
                        }
                    } else {
                        pending.clear()
                        ready = false
                    }
                }
            }
            return
        }

        if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "ultron-" + System.nanoTime())
        } else {
            pending.addLast(text)
        }
    }

    @Synchronized
    fun stop() {
        engine?.stop()
    }

    @Synchronized
    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        pending.clear()
    }
}
