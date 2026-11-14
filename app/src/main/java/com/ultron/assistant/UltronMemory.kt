package com.ultron.assistant

import android.content.Context
import org.json.JSONArray

object UltronMemory {
    private const val PREFS = "ultron_memory"
    private const val MEMORIES = "saved_memories"
    private const val MAX_MEMORIES = 100

    @Synchronized
    fun remember(context: Context, memory: String) {
        val value = memory.trim()
        if (value.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val memories = readMemories(prefs.getString(MEMORIES, "[]"))
        val existing = (0 until memories.length()).mapNotNull { memories.optString(it).takeIf(String::isNotBlank) }
        if (existing.lastOrNull()?.equals(value, ignoreCase = true) == true) return
        val updated = (existing + value).takeLast(MAX_MEMORIES)
        val array = JSONArray()
        updated.forEach { array.put(it) }
        prefs.edit().putString(MEMORIES, array.toString()).apply()
    }

    @Synchronized
    fun recall(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val memories = readMemories(prefs.getString(MEMORIES, "[]"))
        return (0 until memories.length()).mapNotNull { memories.optString(it).takeIf(String::isNotBlank) }
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(MEMORIES).apply()
    }

    private fun readMemories(raw: String?): JSONArray = try {
        JSONArray(raw ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
}