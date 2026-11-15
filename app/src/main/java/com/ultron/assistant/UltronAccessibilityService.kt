package com.ultron.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
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

        fun captureScreenshot(callback: (Boolean, String) -> Unit): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < 30) {
                callback(false, "Automatic screenshots require Android 11 or newer.")
                return true
            }
            service.captureDisplayScreenshot(callback)
            return true
        }

        fun openWhatsAppConversation(contact: String, message: String? = null): Boolean {
            if (contact.isBlank()) return false
            val service = instance ?: return false
            service.beginWhatsAppFlow(contact.trim(), message?.trim()?.takeIf { it.isNotBlank() }, false)
            return true
        }

        fun openWhatsAppGroup(groupName: String, message: String? = null): Boolean {
            if (groupName.isBlank()) return false
            val service = instance ?: return false
            service.beginWhatsAppFlow(groupName.trim(), message?.trim()?.takeIf { it.isNotBlank() }, true)
            return true
        }

        fun openExternalIntent(intent: Intent): Boolean {
            val service = instance ?: return false
            return try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                service.startActivity(intent)
                true
            } catch (_: Exception) { false }
        }

        fun launchInstalledApp(context: Context, packageName: String): Boolean {
            val service = instance ?: return false
            return try {
                val manager = service.packageManager
                val launch = manager.getLaunchIntentForPackage(packageName)
                    ?: manager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), 0
                    ).firstOrNull()?.activityInfo?.let {
                        Intent.makeMainActivity(android.content.ComponentName(it.packageName, it.name))
                    }
                    ?: return false
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                service.startActivity(launch)
                true
            } catch (_: Exception) { false }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var waContact: String? = null
    private var waMessage: String? = null
    private var waGroupMode = false
    private var waStage = 0
    private var waRetries = 0
    private val waAdvance = Runnable { advanceWhatsAppFlow() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    private fun captureDisplayScreenshot(callback: (Boolean, String) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            callback(false, "Automatic screenshots require Android 11 or newer.")
            return
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        try {
                            val hardware = screenshot.hardwareBuffer
                            bitmap = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware.close()
                            if (bitmap == null) {
                                callback(false, "Android did not provide a screenshot image.")
                                return
                            }
                            val name = "ULTRON_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.png"
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ULTRON")
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            if (uri == null) {
                                callback(false, "Couldn't create the screenshot file.")
                                return
                            }
                            val saved = contentResolver.openOutputStream(uri)?.use { output ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            } == true
                            if (saved) {
                                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                                contentResolver.update(uri, done, null, null)
                                callback(true, "Screenshot saved to Pictures/ULTRON.")
                            } else {
                                contentResolver.delete(uri, null, null)
                                callback(false, "Couldn't save the screenshot.")
                            }
                        } catch (e: Exception) {
                            callback(false, "Screenshot failed: ${e.javaClass.simpleName}.")
                        } finally {
                            bitmap?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        callback(false, "Android couldn't capture the screen (error $errorCode).")
                    }
                })
        } catch (e: Exception) {
            callback(false, "Screenshot failed: ${e.javaClass.simpleName}.")
        }
    }

    private fun beginWhatsAppFlow(contact: String, message: String?, groupMode: Boolean) {
        waContact = contact
        waMessage = message
        waGroupMode = groupMode
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
                val newChat = if (waGroupMode) null else findNode(root) { node ->
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    val text = node.text?.toString()?.lowercase().orEmpty()
                    node.isClickable && (desc.contains("new chat") || desc.contains("new conversation") ||
                        text == "new chat" || text == "new conversation" ||
                        node.viewIdResourceName?.contains("fab_new_chat") == true)
                }
                // Group names are searched from WhatsApp's main Chats search,
                // not the New Chat contact picker (which often omits groups).
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
                        retryWhatsApp(1500)
                    } else retryWhatsApp()
                } else retryWhatsApp()
            }
            3 -> {
                val target = waContact.orEmpty()
                val candidates = findNodes(root) { node ->
                    node.isVisibleToUser && (
                        !node.text?.toString().isNullOrBlank() ||
                        !node.contentDescription?.toString().isNullOrBlank()
                    )
                }.mapNotNull { node ->
                    val textLabel = node.text?.toString()?.trim().orEmpty()
                    val description = node.contentDescription?.toString()?.trim().orEmpty()
                    val textScore = contactLabelScore(target, textLabel)
                    val descriptionScore = contactLabelScore(target, description)
                    val score = maxOf(textScore, descriptionScore)
                    if (score >= 65) node to score else null
                }.distinctBy {
                    normalizeContactLabel(
                        it.first.text?.toString()?.takeIf { value -> value.isNotBlank() }
                            ?: it.first.contentDescription?.toString().orEmpty()
                    )
                }.sortedByDescending { it.second }

                val best = candidates.firstOrNull()
                val second = candidates.getOrNull(1)
                // Avoid opening the wrong person when two visible rows are similarly named.
                if (best != null && (second == null || best.second - second.second >= 8)) {
                    val clickable = clickableAncestor(best.first)
                    if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        waStage = if (waMessage == null) 0 else 4
                        waRetries = 0
                        if (waStage == 4) retryWhatsApp(900)
                        else clearWhatsAppFlow()
                    } else retryWhatsApp()
                } else retryWhatsApp(650)
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
                        // The user explicitly dictated a send command. Send only
                        // after the intended contact chat is open and text is filled.
                        waStage = 5
                        waRetries = 0
                        retryWhatsApp(650)
                    } else retryWhatsApp()
                } else retryWhatsApp()
            }
            5 -> {
                val sendButton = findNode(root) { node ->
                    val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                    val description = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    val id = node.viewIdResourceName?.lowercase().orEmpty()
                    node.isVisibleToUser && node.isEnabled && node.isClickable &&
                        (text == "send" || description == "send" ||
                         id.endsWith(":id/send") || id.contains("send"))
                }
                if (sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    clearWhatsAppFlow()
                } else retryWhatsApp(500)
            }
        }
    }

    private fun normalizeContactLabel(value: String): String = value.lowercase(java.util.Locale.US)
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        // Keep speech-recognition variants equivalent to the names in Contacts.
        .replace(Regex("\\bmomma\\b"), "mama")
        .replace(Regex("\\bmamma\\b"), "mama")
        .replace(Regex("\\bmommy\\b"), "mama")
        .replace(Regex("\\bmother\\b"), "mama")
        .replace(Regex("\\bpoppa\\b"), "papa")
        .replace(Regex("\\bpappa\\b"), "papa")
        .replace(Regex("\\bpapi\\b"), "papa")
        .replace(Regex("\\bdaddy\\b"), "papa")
        .replace(Regex("\\bfather\\b"), "papa")

    private fun contactLabelScore(spokenName: String, visibleLabel: String): Int {
        val wanted = normalizeContactLabel(spokenName).split(" ").filter { it.isNotBlank() }.toSet()
        val shown = normalizeContactLabel(visibleLabel).split(" ").filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty() || shown.isEmpty()) return 0
        val w = wanted.joinToString(" ")
        val s = shown.joinToString(" ")
        if (w == s) return 100
        if (s.startsWith(w) || w.startsWith(s)) return 88
        if (s.contains(w) || w.contains(s)) return 84
        val overlap = wanted.intersect(shown).size
        if (overlap == wanted.size) return 80
        if (overlap > 0 && overlap.toFloat() / wanted.size >= 0.75f) return 65
        return 0
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
        waGroupMode = false
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
