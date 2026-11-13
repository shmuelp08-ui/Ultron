
package com.ultron.assistant

import android.content.Context
import org.json.JSONArray

object UltronMemory {

    private const val PREFS = "ultron_memory"
    private const val MEMORIES = "saved_memories"

    fun remember(context: Context, memory: String) {
        if (memory.isBlank()) return

        val prefs = context.getSharedPreferences(
            PREFS, Context.MODE_PRIVATE
        )

        val memories = JSONArray(
            prefs.getString(MEMORIES, "[]")
        )

        memories.put(memory)

        prefs.edit()
            .putString(MEMORIES, memories.toString())
            .apply()
    }

    fun recall(context: Context): List<String> {
        val prefs = context.getSharedPreferences(
            PREFS, Context.MODE_PRIVATE
        )

        val memories = JSONArray(
            prefs.getString(MEMORIES, "[]")
        )

        return (0 until memories.length()).map {
            memories.getString(it)
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(
            PREFS, Context.MODE_PRIVATE
        ).edit().remove(MEMORIES).apply()
    }
}
