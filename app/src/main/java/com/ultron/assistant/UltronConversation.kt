package com.ultron.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object UltronConversation {
    private const val PREFS = "ultron_conversation"
    private const val KEY = "turns"
    private const val MAX_TURNS = 24

    @Synchronized
    fun addTurn(context: Context, role: String, text: String) {
        if (text.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val turns = try {
            JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        turns.put(JSONObject().put("role", role).put("text", text))
        val trimmed = JSONArray()
        val start = (turns.length() - MAX_TURNS).coerceAtLeast(0)
        for (i in start until turns.length()) trimmed.put(turns.getJSONObject(i))
        prefs.edit().putString(KEY, trimmed.toString()).apply()
    }

    @Synchronized
    fun transcript(context: Context): String {
        val turns = try {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until turns.length()).joinToString("\\n") { i ->
            val turn = turns.getJSONObject(i)
            "${turn.optString("role")}: ${turn.optString("text")}"
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
