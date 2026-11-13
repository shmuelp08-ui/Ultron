
package com.ultron.assistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AIController {

    suspend fun ask(
        apiKey: String,
        message: String
    ): String {

        return try {
            val connection = URL(
                "https://api.openai.com/v1/responses"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Authorization", "Bearer $apiKey"
            )
            connection.setRequestProperty(
                "Content-Type", "application/json"
            )
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.doOutput = true

            val body = JSONObject()
                .put("model", "gpt-6")
                .put("input", message)

            connection.outputStream.use {
                it.write(body.toString().toByteArray())
            }

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val json = JSONObject(response)
            val output = json.getJSONArray("output")

            val result = StringBuilder()

            for (i in 0 until output.length()) {
                val content = output.getJSONObject(i)
                    .optJSONArray("content") ?: continue

                for (j in 0 until content.length()) {
                    val item = content.getJSONObject(j)
                    if (item.optString("type") == "output_text") {
                        result.append(item.optString("text"))
                    }
                }
            }

            result.toString().ifBlank { "No response received." }

        } catch (e: Exception) {
            "AI connection error: ${e.message}"
        }
    }
}
