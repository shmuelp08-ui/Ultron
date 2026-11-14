package com.ultron.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Explicitly user-enabled accessibility bridge. It only acts on commands the user
 * speaks to ULTRON; it does not collect or transmit screen contents.
 */
class UltronAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: UltronAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null
        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        fun openWhatsAppConversation(contact: String, message: String? = null): Boolean {
            if (contact.isBlank()) return false
            val service = instance ?: return false
            service.beginWhatsAppFlow(contact.trim(), message?.trim()?.takeIf { it.isNotBlank() })
            return true
        }

        fun launchInstalledApp(context: Context, packageName: String): Boolean {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            return try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                true
            } catch (_: Exception) { false }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var waContact: String? = null
    private var waMessage: String? = null
    private var waStage = 0
    private var waRetries = 0
    private val waAdvance = Runnable { advanceWhatsAppFlow() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    private fun beginWhatsAppFlow(contact: String, message: String?) {
        waContact = contact
        waMessage = message
        waStage = 1
        waRetries = 0
        val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (launch == null) {
            clearWhatsAppFlow()
            return
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            handler.removeCallbacks(waAdvance)
            handler.postDelayed(waAdvance, 900)
        } catch (_: Exception) {
            clearWhatsAppFlow()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            if (waStage != 0) {
                handler.removeCallbacks(waAdvance)
                handler.postDelayed(waAdvance, 300)
            }
        }
    }

    private fun advanceWhatsAppFlow() {
        if (waStage == 0) return
        val root = rootInActiveWindow
        if (root == null) { retryWhatsApp(); return }
        val pkg = root.packageName?.toString()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") {
            retryWhatsApp()
            return
        }

        when (waStage) {
            1 -> {
                // Prefer WhatsApp's New Chat / contact picker so this can reach
                // contacts that do not already have an existing conversation.
                val newChat = findNode(root) { node ->
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    val text = node.text?.toString()?.lowercase().orEmpty()
                    node.isClickable && (desc.contains("new chat") || desc.contains("new conversation") ||
                        text == "new chat" || text == "new conversation" ||
                        node.viewIdResourceName?.contains("fab_new_chat") == true)
                }
                val search = newChat ?: findNode(root) { node ->
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    val text = node.text?.toString()?.lowercase().orEmpty()
                    (desc == "search" || text == "search" || desc.contains("search")) && node.isClickable
                }
                if (search != null && search.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    waStage = 2
                    waRetries = 0
                    retryWhatsApp(450)
                } else retryWhatsApp()
            }
            2 -> {
                val field = findNode(root) { it.className?.toString()?.contains("EditText") == true && it.isVisibleToUser }
                if (field != null) {
                    field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, waContact)
                    }
                    if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        waStage = 3
                        waRetries = 0
                        retryWhatsApp(1100)
                    } else retryWhatsApp()
                } else retryWhatsApp()
            }
            3 -> {
                val target = waContact?.lowercase().orEmpty()
                val match = findNode(root) { node ->
                    val value = node.text?.toString()?.trim()?.lowercase().orEmpty()
                    value == target || (value.length > target.length && value.startsWith(target))
                }
                if (match != null) {
                    val clickable = clickableAncestor(match)
                    if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        waStage = if (waMessage == null) 0 else 4
                        waRetries = 0
                        if (waStage == 4) retryWhatsApp(900)
                        else clearWhatsAppFlow()
                    } else retryWhatsApp()
                } else retryWhatsApp()
            }
            4 -> {
                val fields = findNodes(root) { it.className?.toString()?.contains("EditText") == true && it.isVisibleToUser }
                val composer = fields.lastOrNull()
                if (composer != null) {
                    composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, waMessage)
                    }
                    if (composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        // Intentionally do not press Send. User reviews and sends.
                        clearWhatsAppFlow()
                    } else retryWhatsApp()
                } else retryWhatsApp()
            }
        }
    }

    private fun retryWhatsApp(delay: Long = 350L) {
        waRetries++
        if (waRetries > 12) {
            clearWhatsAppFlow()
            return
        }
        handler.removeCallbacks(waAdvance)
        handler.postDelayed(waAdvance, delay)
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun findNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        if (predicate(root)) found.add(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            found.addAll(findNodes(child, predicate))
        }
        return found
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return node
    }

    private fun clearWhatsAppFlow() {
        handler.removeCallbacks(waAdvance)
        waContact = null
        waMessage = null
        waStage = 0
        waRetries = 0
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        clearWhatsAppFlow()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
