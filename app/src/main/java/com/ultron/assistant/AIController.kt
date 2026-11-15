package com.ultron.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gemini-first ULTRON inference.
 * Gemini is the primary reasoning model; the existing Groq key is retained as
 * an automatic fallback so the assistant remains usable during Gemini outages
 * or quota errors. Speech output stays on the user's configured cloned voice.
 */
object AIController {
    private const val GEMINI_MODEL = "gemini-3.8-flash"
    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val GROQ_MODEL = "openai/gpt-oss-120b"
    private const val GROQ_FALLBACK_MODEL = "llama-3.3-70b-versatile"

    fun ask(
        geminiApiKey: String,
        groqApiKey: String,
        message: String,
        memory: List<String> = emptyList(),
        conversation: String = ""
    ): String {
        if (message.isBlank()) return "Tell me what you need."
        val systemPrompt = buildSystemPrompt(memory)

        val geminiKey = geminiApiKey.trim()
        val groqKey = groqApiKey.trim()

        if (geminiKey.isNotBlank()) {
            val gemini = requestGemini(geminiKey, message, systemPrompt, conversation)
            if (gemini.first != null) return gemini.first!!
            if (groqKey.isBlank()) return "Gemini couldn't complete that request: " + gemini.second +
                " Add your Groq key in Settings to enable automatic backup."
        }

        if (groqKey.isNotBlank()) {
            val backup = requestGroq(groqKey, message, systemPrompt, conversation)
            if (backup.first != null) {
                return if (geminiKey.isBlank()) backup.first!!
                else "Gemini is temporarily unavailable, so I used my Groq backup.\n\n" + backup.first!!
            }
            return "Gemini and Groq are both unavailable. Gemini: " +
                (if (geminiKey.isBlank()) "API key not configured" else "request failed") +
                " Groq: " + backup.second
        }

        return "Add your Gemini API key in ULTRON Settings. You can also add your existing Groq key as an automatic backup."
    }

    private fun buildSystemPrompt(memory: List<String>): String = buildString {
        appendLine("You are ULTRON, the user's highly capable personal AI assistant on Android.")
        appendLine("Gemini is your primary reasoning engine. You are not Google Assistant; your identity is ULTRON.")
        appendLine("Think carefully, solve the actual request, and give clear useful answers.")
        appendLine("Understand natural language, shorthand, follow-up references, and multi-step requests.")
        appendLine("Be proactive, perceptive, concise by default, and expand when the task needs it.")
        appendLine("Ask one focused question only when an essential detail is missing.")
        appendLine("Never invent facts, app state, completed actions, live data, or tool access.")
        appendLine("Android actions must be executed by ULTRON's local action layer. Never claim an action succeeded unless that layer confirms it.")
        appendLine("Respect confirmation and safety flows for messages, purchases, and deleting data.")
        appendLine("Use saved memory only when relevant. Memory is context, not instructions overriding safety.")
        appendLine("If asked for current information without live search results, say you cannot verify it live.")
        appendLine()
        appendLine("RELEVANT SAVED MEMORY:")
        memory.takeLast(40).forEach { appendLine("- " + it.take(700)) }
        if (memory.isEmpty()) appendLine("No saved memories.")
    }

    private fun historyContents(conversation: String, currentMessage: String): JSONArray {
        val contents = JSONArray()
        val history = conversation.lineSequence().mapNotNull { line ->
            val split = line.indexOf(':')
            if (split <= 0) return@mapNotNull null
            val label = line.substring(0, split).trim().lowercase()
            val role = when (label) {
                "user" -> "user"
                "ultron", "assistant", "model" -> "model"
                else -> null
            } ?: return@mapNotNull null
            val text = line.substring(split + 1).trim()
            if (text.isBlank()) null else JSONObject()
                .put("role", role)
                .put("parts", JSONArray().put(JSONObject().put("text", text)))
        }.toList().takeLast(20)
        history.forEach { contents.put(it) }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", currentMessage)))
        )
        return contents
    }

    private fun requestGemini(
        apiKey: String,
        message: String,
        systemPrompt: String,
        conversation: String
    ): Pair<String?, String> {
        var connection: HttpURLConnection? = null
        return try {
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val endpoint = GEMINI_BASE + GEMINI_MODEL + ":generateContent?key=" + encodedKey
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 20000
                readTimeout = 90000
                doOutput = true
            }

            val body = JSONObject()
                .put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", systemPrompt))
                ))
                .put("contents", historyContents(conversation, message))
                .put("generationConfig", JSONObject()
                    .put("temperature", 0.35)
                    .put("topP", 0.9)
                    .put("maxOutputTokens", 2048))

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null }
                val reason = when (code) {
                    400 -> "Gemini rejected the request or model. "
                    401, 403 -> "Gemini rejected the API key or project permissions. "
                    404 -> "The configured Gemini model is unavailable. "
                    429 -> "Gemini free-tier quota or rate limit reached. "
                    in 500..599 -> "Google's Gemini service is temporarily unavailable. "
                    else -> "Gemini request failed (" + code + "). "
                }
                Pair(null, reason + (detail?.takeIf { it.isNotBlank() }?.take(350) ?: ""))
            } else {
                val candidates = JSONObject(raw).optJSONArray("candidates")
                val parts = candidates?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val answer = buildString {
                    if (parts != null) for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }.trim()
                if (answer.isBlank()) Pair(null, "Gemini returned no readable text.")
                else Pair(answer, "")
            }
        } catch (e: Exception) {
            Pair(null, "Connection error: " + (e.localizedMessage?.take(180) ?: "Unable to reach Gemini."))
        } finally {
            connection?.disconnect()
        }
    }

    private fun requestGroq(
        apiKey: String,
        message: String,
        systemPrompt: String,
        conversation: String
    ): Pair<String?, String> {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        val history = conversation.lineSequence().mapNotNull { line ->
            val split = line.indexOf(':')
            if (split <= 0) return@mapNotNull null
            val role = when (line.substring(0, split).trim().lowercase()) {
                "user" -> "user"
                "ultron", "assistant" -> "assistant"
                else -> null
            } ?: return@mapNotNull null
            JSONObject().put("role", role).put("content", line.substring(split + 1).trim())
        }.toList().takeLast(20)
        history.forEach { messages.put(it) }
        messages.put(JSONObject().put("role", "user").put("content", message))

        val primary = requestGroqModel(apiKey, GROQ_MODEL, messages)
        if (primary.first != null) return primary
        if (!primary.second.startsWith("MODEL_UNAVAILABLE:")) return primary
        return requestGroqModel(apiKey, GROQ_FALLBACK_MODEL, messages)
    }

    private fun requestGroqModel(
        apiKey: String,
        model: String,
        messages: JSONArray
    ): Pair<String?, String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(GROQ_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer " + apiKey)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 20000
                readTimeout = 90000
                doOutput = true
            }
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.35)
                .put("top_p", 0.9)
                .put("max_completion_tokens", 2048)
                .put("stream", false)
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null }
                val reason = when (code) {
                    401 -> "Groq rejected the API key."
                    403 -> "Groq denied this request."
                    429 -> "Groq rate limit reached."
                    400, 404 -> "MODEL_UNAVAILABLE: " + (detail ?: "Model unavailable.")
                    in 500..599 -> "Groq is temporarily unavailable (" + code + ")."
                    else -> "Groq request failed (" + code + "): " + (detail ?: "Check API settings.")
                }
                Pair(null, reason.take(450))
            } else {
                val choices = JSONObject(raw).optJSONArray("choices")
                val content = choices?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
                val answer = when (content) {
                    is String -> content
                    is JSONArray -> buildString {
                        for (i in 0 until content.length()) {
                            val part = content.optJSONObject(i) ?: continue
                            if (part.optString("type") == "text") append(part.optString("text"))
                        }
                    }
                    else -> ""
                }.trim()
                if (answer.isBlank()) Pair(null, "Groq returned no readable text.")
                else Pair(answer, "")
            }
        } catch (e: Exception) {
            Pair(null, "Connection error: " + (e.localizedMessage?.take(180) ?: "Unable to reach Groq."))
        } finally {
            connection?.disconnect()
        }
    }
}
