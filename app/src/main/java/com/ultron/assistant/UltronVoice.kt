package com.ultron.assistant

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Uses Fish Audio TTS when configured; falls back to Android TTS otherwise.
 */
object UltronVoice {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var player: MediaPlayer? = null
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val app = context.applicationContext
        val fishKey = UltronSettings.get(app, "fish_api_key")
        val voiceId = UltronSettings.get(app, "fish_voice_id")
        if (!fishKey.isNullOrBlank() && !voiceId.isNullOrBlank()) {
            Thread {
                try {
                    val connection = (URL("https://api.fish.audio/v1/tts").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $fishKey")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("model", "s2.1-pro-free")
                        connectTimeout = 20000
                        readTimeout = 90000
                        doOutput = true
                    }
                    val body = JSONObject()
                        .put("text", text.take(4000))
                        .put("reference_id", voiceId)
                        .put("format", "mp3")
                        .put("latency", "balanced")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        connection.disconnect()
                        throw IllegalStateException("Fish Audio error ($code): ${error.take(180)}")
                    }
                    val audio = connection.inputStream.use { it.readBytes() }
                    connection.disconnect()
                    if (audio.isEmpty()) throw IllegalStateException("Fish Audio returned an empty audio file.")
                    val file = File.createTempFile("ultron_speech_", ".mp3", app.cacheDir)
                    file.writeBytes(audio)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            player?.let { oldPlayer ->
                                try { oldPlayer.stop() } catch (_: Exception) {}
                                oldPlayer.release()
                            }
                            player = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnPreparedListener { prepared ->
                                    if (player === prepared) prepared.start() else prepared.release()
                                }
                                setOnCompletionListener {
                                    it.release()
                                    file.delete()
                                    if (player === it) player = null
                                }
                                setOnErrorListener { failed, _, _ ->
                                    failed.release()
                                    file.delete()
                                    if (player === failed) player = null
                                    speakNative(app, text)
                                    true
                                }
                                prepareAsync()
                            }
                        } catch (_: Exception) {
                            file.delete()
                            speakNative(app, text)
                        }
                    }
                } catch (_: Exception) {
                    // Keep the assistant audible if the network/provider is unavailable.
                    android.os.Handler(android.os.Looper.getMainLooper()).post { speakNative(app, text) }
                }
            }.start()
            return
        }
        speakNative(app, text)
    }

    @Synchronized
    private fun speakNative(context: Context, text: String) {
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
    fun stop() {
        engine?.stop()
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
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
