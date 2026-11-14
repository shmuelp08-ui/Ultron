package com.ultron.assistant

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AssemblyAI-TTS-1 playback. The voice ID must be a clone created in the
 * user's AssemblyAI dashboard. Configured clone failures are surfaced instead
 * of silently switching to Android's unrelated system voice.
 */
object AssemblyAITTS {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var player: MediaPlayer? = null

    fun speak(context: Context, text: String, apiKey: String, voiceId: String) {
        if (text.isBlank()) return
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://tts.us.assemblyai.com/v1/openai/audio/speech").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 20000
                    readTimeout = 120000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "audio/mpeg")
                }
                val body = JSONObject()
                    .put("model", "assemblyai-tts-en")
                    .put("voice", voiceId)
                    .put("input", text.take(5000))
                    .put("response_format", "mp3")
                    .put("extra_body", JSONObject().put("extend_biometric_consent", true))
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw Exception("AssemblyAI TTS failed ($code): ${error.take(300)}")
                }
                val file = File(context.cacheDir, "ultron_assemblyai_reply.mp3")
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                if (!file.exists() || file.length() < 256L) throw Exception("AssemblyAI returned an empty audio response.")
                main.post {
                    try {
                        player?.let { old -> try { old.stop() } catch (_: Exception) {}; old.release() }
                        player = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setOnPreparedListener { it.start() }
                            setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null }
                            setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; Toast.makeText(context, "ULTRON couldn't play the cloned voice audio.", Toast.LENGTH_LONG).show(); true }
                            prepareAsync()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cloned voice playback failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(context, "AssemblyAI cloned voice failed: ${e.localizedMessage ?: "Check API key and voice ID."}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    fun stop() {
        main.post {
            player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            player = null
        }
    }
}
