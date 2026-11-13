package com.ultron.assistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AIController {
    fun ask(apiKey: String, message: String, memory: List<String> = emptyList(), conversation: String = ""): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://api.groq.com/openai/v1/responses").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 20000
                readTimeout = 60000
                doOutput = true
            }
            val instructions = """
                You are ULTRON, a capable personal AI assistant living on the user's Android phone.
                Speak naturally, confidently, and concisely. Be helpful, practical, and honest.
                Never claim to control the phone unless a connected Android action actually succeeded.
                Ask for confirmation before sending messages, making purchases, deleting data, or taking consequential actions.
                Relevant saved user notes:
                ${memory.takeLast(30).joinToString("\n")}
            """.trimIndent()
            val body = JSONObject()
                .put("model", "openai/gpt-oss-120b")
                .put("instructions", instructions)
                .put("input", buildString {
                    if (conversation.isNotBlank()) {
                        append("Recent conversation:\\n")
                        append(conversation)
                        append("\\n\\n")
                    }
                    append("Current message: ")
                    append(message)
                })
                .put("store", false)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                return "AI request failed ($code): ${detail?.takeIf { it.isNotBlank() } ?: "Check your Groq API key and free-tier limits."}"
            }
            val output = JSONObject(response).optJSONArray("output") ?: return "I received an empty response."
            val result = StringBuilder()
            for (i in 0 until output.length()) {
                val items = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    if (item.optString("type") == "output_text") result.append(item.optString("text"))
                }
            }
            result.toString().ifBlank { "I received an empty response." }
        } catch (e: Exception) {
            "Connection error: ${e.localizedMessage ?: "Unable to reach the AI service."}"
        } finally {
            connection?.disconnect()
        }
    }
}
