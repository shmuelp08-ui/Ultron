
package com.ultron.assistant

import android.content.Context

object UltronSettings {

    private const val PREFS = "ultron_settings"

    fun save(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }

    fun get(context: Context, key: String): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
    }

    fun saveName(context: Context, name: String) {
        save(context, "assistant_name", name)
    }

    fun assistantName(context: Context): String {
        return get(context, "assistant_name") ?: "ULTRON"
    }
}
