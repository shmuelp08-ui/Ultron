package com.ultron.assistant

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object FishVoice {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    fun clone(context: Context, uri: Uri, apiKey: String, done: (Boolean, String) -> Unit) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val boundary = "UltronVoice" + System.currentTimeMillis()
                val audio = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("Couldn't read the selected audio file.")
                connection = (URL("https://api.fish.audio/model").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 120000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                connection.outputStream.use { out ->
                    fun field(name: String, value: String) {
                        out.write("--$boundary\r\n".toByteArray())
                        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                    }
                    field("type", "tts"); field("title", "ULTRON Imported Voice")
                    field("description", "Private voice profile imported into ULTRON")
                    field("visibility", "private"); field("train_mode", "fast")
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"voices\"; filename=\"ultron-reference-audio\"\r\n".toByteArray())
                    out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                    out.write(audio); out.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw Exception("Fish Audio rejected the clone request ($code): ${body.take(400)}")
                val json = JSONObject(body)
                val id = json.optString("_id").ifBlank { json.optString("id") }
                if (id.isBlank()) throw Exception("Fish Audio returned no voice ID: ${body.take(250)}")
                val state = json.optString("state", "trained")
                if (state == "failed") throw Exception("Fish Audio could not train this voice sample.")
                UltronSettings.save(context, "fish_voice_id", id)
                main.post { done(true, "Voice profile created successfully (status: $state).") }
            } catch (e: Exception) { main.post { done(false, e.message ?: "Voice cloning failed.") } }
            finally { connection?.disconnect() }
        }.start()
    }

    fun speak(context: Context, text: String, apiKey: String, voiceId: String) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.fish.audio/v1/tts").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 120000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("model", "s2.1-pro-free")
                }
                val body = JSONObject().put("text", text).put("reference_id", voiceId).put("format", "mp3")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw Exception("Fish Audio speech failed ($code): ${error.take(250)}")
                }
                val file = java.io.File(context.cacheDir, "ultron_voice_reply.mp3")
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                main.post {
                    try {
                        player?.release()
                        player = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setOnPreparedListener { it.start() }
                            setOnCompletionListener { it.release(); if (player === it) player = null }
                            setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; true }
                            prepareAsync()
                        }
                    } catch (_: Exception) { UltronVoice.speakSystemFallback(context, text) }
                }
            } catch (_: Exception) { main.post { UltronVoice.speakSystemFallback(context, text) } }
            finally { connection?.disconnect() }
        }.start()
    }
}
