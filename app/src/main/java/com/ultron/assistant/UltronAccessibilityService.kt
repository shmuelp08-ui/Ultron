package com.ultron.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** User-enabled bridge for Android navigation actions. Android requires the user to enable it in Settings. */
class UltronAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: UltronAccessibilityService? = null
        fun isEnabled(): Boolean = instance != null
        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are intentionally not recorded or transmitted.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
