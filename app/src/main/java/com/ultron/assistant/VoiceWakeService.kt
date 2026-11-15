package com.ultron.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.net.Uri
import android.provider.ContactsContract

class VoiceWakeService : Service() {

    companion object {
        const val ACTION_STOP = "com.ultron.assistant.STOP_VOICE"
        private const val CHANNEL_ID = "ultron_voice_channel"
        private const val NOTIFICATION_ID = 404
        // User-provided Philadelphia, PA WhatsApp destination (E.164 digits).
        private const val WHATSAPP_NUMBER = "12154320383"
    }

    private var recognizer: SpeechRecognizer? = null
    private var recognitionGeneration = 0
    private var active = false
    private var waitingForCommand = false
    @Volatile private var partialTranscript = ""
    private var launchQueuedForUnlock = false
    @Volatile private var voicePauseUntil = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    // Service Context is not attached during construction; initialize Handler in onCreate.
    private lateinit var restartHandler: android.os.Handler
    private val restartTask = Runnable { if (active) startRecognition() }

    private val apps = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "gmail" to "com.google.android.gm",
        "play store" to "com.android.vending",
        "google play" to "com.android.vending",
        "messages" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer"
    )

    override fun onCreate() {
        super.onCreate()
        restartHandler = android.os.Handler(android.os.Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            UltronSettings.save(this, "voice_enabled", "false")
            UltronSettings.save(this, "voice_status", "VOICE OFF")
            active = false
            restartHandler.removeCallbacksAndMessages(null)
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote the service immediately. Android 14+ enforces microphone
        // foreground-service prerequisites and may throw SecurityException.
        try {
            val notification = buildNotification("Starting voice system...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            active = false
            UltronSettings.save(this, "voice_status", "MICROPHONE BLOCKED — CHECK PERMISSIONS")
            UltronSettings.save(this, "voice_enabled", "false")
            stopSelf()
            return START_NOT_STICKY
        } catch (e: RuntimeException) {
            active = false
            UltronSettings.save(this, "voice_status", "VOICE START FAILED — " + e.javaClass.simpleName)
            UltronSettings.save(this, "voice_enabled", "false")
            stopSelf()
            return START_NOT_STICKY
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            UltronSettings.save(this, "voice_status", "MICROPHONE PERMISSION DENIED")
            UltronSettings.save(this, "voice_enabled", "false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!active) {
            active = true
            acquireWakeLock()
            try {
                startRecognition()
            } catch (e: RuntimeException) {
                updateNotification("Voice startup failed — check microphone permissions")
                active = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                UltronSettings.save(this, "voice_enabled", "false")
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ULTRON:VoiceListening"
            )
            wakeLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ULTRON Voice Listening",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Keeps ULTRON voice listening active"

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, VoiceWakeService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            405,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            406,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ULTRON is listening")
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun startRecognition() {
        if (!active) return
        val pauseRemaining = voicePauseUntil - android.os.SystemClock.elapsedRealtime()
        if (pauseRemaining > 0L) {
            restartListening(pauseRemaining + 250L)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            UltronSettings.save(this, "voice_status", "SPEECH RECOGNITION UNAVAILABLE — RETRYING")
            updateNotification("Speech recognition unavailable — retrying")
            restartListening(3000)
            return
        }

        try {
            // Invalidate callbacks from the previous recognizer before replacing it.
            recognitionGeneration += 1
            val generation = recognitionGeneration
            val previous = recognizer
            recognizer = null
            try { previous?.cancel() } catch (_: RuntimeException) { }
            try { previous?.destroy() } catch (_: RuntimeException) { }

            val currentRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
            recognizer = currentRecognizer

            partialTranscript = ""
            currentRecognizer.setRecognitionListener(
                object : RecognitionListener {
                    private fun isCurrent(): Boolean =
                        active && generation == recognitionGeneration &&
                            recognizer === currentRecognizer

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!isCurrent()) return
                        UltronSettings.save(this@VoiceWakeService, "voice_status", "LISTENING FOR ULTRON")
                        updateNotification(
                            if (waitingForCommand) {
                                "ULTRON activated — say your command"
                            } else {
                                "Listening for ULTRON..."
                            }
                        )
                    }

                    override fun onBeginningOfSpeech() {
                        if (!isCurrent()) return
                        UltronSettings.save(this@VoiceWakeService, "voice_status", "HEARING YOU")
                        updateNotification("Hearing you…")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        if (!isCurrent()) return
                        UltronSettings.save(this@VoiceWakeService, "voice_status", "PROCESSING SPEECH")
                        updateNotification("Processing speech…")
                    }

                    override fun onError(error: Int) {
                        if (!isCurrent()) return
                        UltronSettings.save(this@VoiceWakeService, "voice_status", "RECONNECTING — SPEECH ERROR " + error)
                        updateNotification("Speech error " + error + " — reconnecting")
                        val retryDelay = when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 500L
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2200L
                            else -> 1100L
                        }
                        restartListening(retryDelay)
                    }

                    override fun onResults(results: Bundle?) {
                        if (!isCurrent()) return

                        val finalOptions = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.map { it.trim().lowercase() }
                            ?: emptyList()
                        val partial = partialTranscript.trim()
                        partialTranscript = ""
                        // Keep the longest useful hypothesis. Android sometimes emits
                        // a truncated final result after a complete partial transcript.
                        val spokenOptions = (finalOptions + listOf(partial))
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sortedByDescending { it.length }

                        processSpeech(spokenOptions)
                        // Give command handling/TTS a moment before reopening the recognizer.
                        restartListening(1400)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!isCurrent()) return
                        val candidates = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        val best = candidates.maxByOrNull { it.length }
                        if (best != null && best.length >= partialTranscript.length) {
                            partialTranscript = best
                        }
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

            listen(currentRecognizer)

        } catch (e: Exception) {
            UltronSettings.save(this, "voice_status", "RECONNECTING — " + e.javaClass.simpleName)
            restartListening(2000)
        }
    }

    private fun listen(target: SpeechRecognizer) {
        if (!active || recognizer !== target) return

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Give names and longer natural-language commands enough time to finish.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        try {
            UltronSettings.save(this, "voice_status", "STARTING LISTENER")
            target.startListening(intent)
        } catch (e: Exception) {
            UltronSettings.save(this, "voice_status", "RECONNECTING — " + e.javaClass.simpleName)
            restartListening(1500)
        }
    }

    private fun restartListening(delay: Long) {
        if (!active) return

        restartHandler.removeCallbacks(restartTask)
        restartHandler.postDelayed(restartTask, delay)
    }

    private fun processSpeech(options: List<String>) {
        if (options.isEmpty()) return

        // Try every recognition alternative; the first result is not always best.
        for (spoken in options) {
            if (spoken.isBlank()) continue

            if (waitingForCommand) {
                waitingForCommand = false
                handleCommand(spoken)
                return
            }

            val wakeIndex = spoken.indexOf("ultron")
            if (wakeIndex >= 0) {
                val command = spoken.substring(wakeIndex + "ultron".length).trim()
                if (command.isNotBlank()) {
                    handleCommand(command)
                } else {
                    waitingForCommand = true
                    UltronSettings.save(this, "voice_status", "ULTRON ACTIVATED — SAY COMMAND")
                    updateNotification("ULTRON activated — speak now")
                }
                return
            }
        }
    }

    private fun normalizeCommand(value: String): String {
        return value.lowercase(java.util.Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            // Common family-name pronunciation variants produced by speech recognition.
            .replace(Regex("\\b(mom|momma|mamma|mommy|mum|mother|ma)\\b"), "mama")
            .replace(Regex("\\b(dad|daddy|father|poppa|pappa|pops|pop|papi|pa)\\b"), "papa")
    }

    private fun handleCommand(command: String) {
        val spoken = normalizeCommand(command)
        if (spoken.isBlank()) return

        // Read the phone's real local clock; never ask the AI to guess the time.
        if (spoken.matches(Regex(".*\\b(time is it|current time|tell me the time|what time|the time)\\b.*"))) {
            val now = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date())
            val answer = "It's $now."
            UltronConversation.addTurn(this, "User", command)
            UltronConversation.addTurn(this, "ULTRON", answer)
            updateNotification(answer)
            speakResponse(answer)
            return
        }

        // Explicit natural-language memory commands.
        val rememberMatch = Regex("^(?:please )?remember(?: that)? (.+)$").find(spoken)
        if (rememberMatch != null) {
            val note = rememberMatch.groupValues[1].trim()
            if (note.isNotBlank()) {
                UltronMemory.remember(this, note)
                val answer = "Got it. I'll remember that."
                UltronConversation.addTurn(this, "User", command)
                UltronConversation.addTurn(this, "ULTRON", answer)
                updateNotification(answer)
                speakResponse(answer)
                return
            }
        }
        if (spoken.contains("what do you remember") || spoken == "list my memories") {
            val saved = UltronMemory.recall(this)
            val answer = if (saved.isEmpty()) "I don't have any saved memories yet."
                else "Here's what I remember: " + saved.takeLast(8).joinToString(". ")
            UltronConversation.addTurn(this, "User", command)
            UltronConversation.addTurn(this, "ULTRON", answer)
            updateNotification(answer.take(120))
            speakResponse(answer)
            return
        }

        // Android screen capture commands.
        if (spoken.contains("screenshot") || spoken.contains("screen shot") ||
            spoken.contains("capture screen") || spoken.contains("take a picture of my screen")) {
            val started = UltronAccessibilityService.captureScreenshot { success, message ->
                updateNotification(message)
                speakResponse(message)
                UltronConversation.addTurn(this, "ULTRON", message)
            }
            if (!started) {
                val message = "Enable ULTRON Phone Control in Android Accessibility settings to take screenshots by voice."
                updateNotification(message)
                speakResponse(message)
            }
            return
        }

        if (spoken.contains("screen recording") || spoken.contains("record my screen") ||
            spoken.contains("start recording my screen")) {
            val message = "Android requires its own screen-sharing approval before every new recording session. ULTRON cannot bypass that system security prompt. I can start a recording only after you approve it."
            updateNotification(message)
            speakResponse(message)
            return
        }

        // WhatsApp contact navigation is driven through the user-enabled Accessibility bridge.
        if (handleWhatsAppCommand(spoken)) return
        // Allow natural commands such as "open Mama" or "find Papa" to resolve
        // against the phone's real contacts before treating them as app names.
        if (handleDirectContactCommand(spoken)) return

        // Phone-wide navigation is available only after the user enables ULTRON Accessibility.
        val globalAction = when {
            spoken in listOf("go home", "home screen", "open home screen") -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            spoken in listOf("go back", "back", "press back") -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            spoken.contains("recent apps") || spoken == "show recent" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            spoken.contains("show notifications") || spoken == "open notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            spoken.contains("quick settings") || spoken == "open control panel" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            spoken == "lock phone" || spoken == "lock screen" -> if (android.os.Build.VERSION.SDK_INT >= 28) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
            else -> -1
        }
        if (globalAction != -1) {
            val success = UltronAccessibilityService.perform(globalAction)
            val message = if (success) "Phone action completed." else "Enable ULTRON Phone Control in Android Accessibility settings first."
            updateNotification(message)
            speakResponse(message)
            return
        }

        if (spoken.contains("camera")) {
            openActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
            updateNotification("Opening camera")
            speakResponse("Opening camera.")
            return
        }

        if (spoken.contains("settings")) {
            openActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            updateNotification("Opening settings")
            speakResponse("Opening settings.")
            return
        }

        // Strip common launch wording, then compare against installed app labels.
        val appRequest = spoken
            .replace(Regex("^(please )?(open|launch|start|run|go to)\\s+"), "")
            .replace(Regex("^the\\s+"), "")
            .replace(Regex("\\s+(please|for me)$"), "")
            .trim()

        // Explicit aliases work even when Android returns a localized app label.
        val aliasedPackage = apps[appRequest]
        if (aliasedPackage != null) {
            val aliasIntent = packageManager.getLaunchIntentForPackage(aliasedPackage)
            if (aliasIntent != null) {
                val opened = launchAppPackage(aliasedPackage, aliasIntent)
                val response = when {
                    launchQueuedForUnlock -> "Unlock your phone and tap the ULTRON notification to open $appRequest."
                    opened -> "Opening $appRequest."
                    else -> "I couldn't open $appRequest. Android may have blocked the launch."
                }
                updateNotification(response)
                speakResponse(response)
            } else {
                updateNotification(appRequest + " is not installed or cannot be launched")
                speakResponse(appRequest + " is not available on this phone.")
            }
            return
        }

        if (appRequest.isBlank()) {
            updateNotification("Which app should I open?")
            speakResponse("Which app should I open?")
            return
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installedApps = packageManager.queryIntentActivities(launcherIntent, 0)

        val compactRequest = appRequest.replace(" ", "")
        val match = installedApps
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString() ?: return@mapNotNull null
                val normalizedLabel = normalizeCommand(label)
                if (normalizedLabel.isBlank()) return@mapNotNull null
                Triple(info.activityInfo.packageName, normalizedLabel, label)
            }
            .distinctBy { it.first }
            .filter { (_, label, _) ->
                val compactLabel = label.replace(" ", "")
                label == appRequest || compactLabel == compactRequest ||
                    label.startsWith(appRequest) || label.contains(appRequest) ||
                    (appRequest.length >= 4 && appRequest.contains(label))
            }
            .minByOrNull { (_, label, _) ->
                val compactLabel = label.replace(" ", "")
                when {
                    label == appRequest -> 0
                    compactLabel == compactRequest -> 1
                    label.startsWith(appRequest) -> 2
                    label.contains(appRequest) -> 3
                    else -> 4
                }
            }

        if (match != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(match.first)
            if (launchIntent != null) {
                val opened = launchAppPackage(match.first, launchIntent)
                val response = when {
                    launchQueuedForUnlock -> "Unlock your phone and tap the ULTRON notification to open ${match.third}."
                    opened -> "Opening ${match.third}."
                    else -> "Android blocked opening ${match.third}."
                }
                updateNotification(response)
                speakResponse(response)
            } else {
                updateNotification("Can't launch ${match.third}")
            }
            return
        }

        // If it is not an app-launch request, treat it as a natural AI question.
        val apiKey = UltronSettings.get(this, "openai_api_key")
        if (apiKey.isNullOrBlank()) {
            updateNotification("AI key needed — open ULTRON app")
            speakResponse("I can answer that, but first add your Groq API key in the ULTRON app.")
            return
        }

        val recentConversation = UltronConversation.transcript(this)
        UltronConversation.addTurn(this, "User", command)
        updateNotification("ULTRON is thinking...")
        Thread {
            val answer = AIController.ask(
                apiKey,
                command,
                UltronMemory.recall(this),
                recentConversation
            )
            UltronConversation.addTurn(this, "ULTRON", answer)
            android.os.Handler(mainLooper).post {
                if (active) {
                    updateNotification(answer.take(120))
                    speakResponse(answer)
                }
            }
        }.start()
    }

    private fun handleDirectContactCommand(command: String): Boolean {
        val match = Regex("^(?:please )?(?:open|launch|go to|find|show me|show) (?:my )?(.+)$")
            .find(command) ?: return false
        val contact = match.groupValues[1]
            .replace(Regex("\\s+(?:whatsapp )?(?:chat|conversation)$"), "")
            .removeSuffix(" whatsapp")
            .removeSuffix("'s")
            .trim()
        if (contact.isBlank()) return false

        // Only intercept the command when a real contact match exists. This
        // leaves ordinary app launching and general questions untouched.
        if (resolveWhatsAppNumber(contact) == null) return false
        return openWhatsAppContact(contact, null)
    }

    private fun handleWhatsAppCommand(command: String): Boolean {
        // Explicit group-chat intent: search the existing WhatsApp Chats list,
        // where group conversations appear, rather than the contact picker.
        val groupMatch = listOf(
            Regex("^(?:open|find|show|go to) (?:my )?(?:whatsapp )?(?:group|group chat) (.+)$"),
            Regex("^(?:open|find|show|go to) (?:my )?whatsapp group chat (.+)$"),
            Regex("^open group chat (.+) on whatsapp$")
        ).firstNotNullOfOrNull { it.find(command) }
        if (groupMatch != null) {
            val groupName = groupMatch.groupValues[1].trim().removeSuffix(" on whatsapp").trim()
            if (groupName.isBlank()) return false
            val started = UltronAccessibilityService.openWhatsAppGroup(groupName)
            val response = if (started) "Searching WhatsApp groups for $groupName."
                else "I couldn't start group search. Make sure WhatsApp is installed and ULTRON Phone Control is enabled."
            updateNotification(response)
            speakResponse(response)
            return true
        }
        val contactAndMessage = listOf(
            Regex("^(?:send|text|message) (.+?) to (.+?) on whatsapp$"),
            Regex("^whatsapp message (.+?) to (.+)$"),
            Regex("^send whatsapp message to (.+?) saying (.+)$")
        )
        val sendToSaying = Regex("^send whatsapp message to (.+?) saying (.+)$").find(command)
        if (sendToSaying != null) {
            return openWhatsAppContact(sendToSaying.groupValues[1].trim(), sendToSaying.groupValues[2].trim())
        }

        for (pattern in contactAndMessage) {
            val match = pattern.find(command) ?: continue
            val message = match.groupValues[1].trim()
            val contact = match.groupValues[2].trim()
            return openWhatsAppContact(contact, message)
        }

        val contactWithMessage = Regex("^message (.+?) on whatsapp saying (.+)$").find(command)
        if (contactWithMessage != null) {
            return openWhatsAppContact(contactWithMessage.groupValues[1].trim(), contactWithMessage.groupValues[2].trim())
        }

        val contactPatterns = listOf(
            Regex("^(?:open|go to|show me) (?:my )?(?:whatsapp )?(?:chat|conversation) with (.+?)(?: on whatsapp)?$"),
            Regex("^(?:open|go to) whatsapp (?:chat|conversation) with (.+?)(?: on whatsapp)?$"),
            Regex("^(?:open|go to) (.+?) on whatsapp$")
        )
        for (pattern in contactPatterns) {
            val match = pattern.find(command) ?: continue
            val contact = match.groupValues[1].trim()
            if (contact.isNotBlank()) return openWhatsAppContact(contact, null)
        }

        if (command == "whatsapp" || command == "open whatsapp" || command == "go to whatsapp" ||
            command == "go on my whatsapp" || command == "open my whatsapp" || command == "launch whatsapp") {
            val intent = packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            if (intent == null) {
                speakResponse("WhatsApp isn't installed.")
                updateNotification("WhatsApp isn't installed")
            } else openActivity(intent)
            return true
        }
        return false
    }

    private fun contactMatchScore(spokenName: String, savedName: String): Int {
        val wanted = normalizeCommand(spokenName).split(" ").filter { it.isNotBlank() }.toSet()
        val candidate = normalizeCommand(savedName).split(" ").filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty() || candidate.isEmpty()) return 0
        val w = wanted.joinToString(" ")
        val c = candidate.joinToString(" ")
        if (w == c) return 100
        if (c.startsWith(w) || w.startsWith(c)) return 88
        if (c.contains(w) || w.contains(c)) return 84
        val overlap = wanted.intersect(candidate).size
        if (overlap == wanted.size) return 80
        if (overlap > 0 && overlap.toFloat() / wanted.size >= 0.75f) return 65
        return 0
    }

    private fun normalizeWhatsAppNumber(rawNumber: String, normalizedNumber: String): String? {
        val source = normalizedNumber.trim().takeIf { it.startsWith("+") } ?: rawNumber.trim()
        val digits = source.filter { it.isDigit() }
        if (digits.length !in 7..15) return null
        if (source.startsWith("+")) return digits

        // Contacts exported from the US often omit +1; Israeli local mobile
        // numbers often begin with 05. Convert those common local formats.
        return when {
            digits.length == 10 && digits.startsWith("05") -> "972" + digits.drop(1)
            digits.length == 10 -> "1" + digits
            digits.length == 11 && digits.startsWith("1") -> digits
            else -> digits
        }
    }

    private fun resolveWhatsAppNumber(contact: String): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val wanted = normalizeCommand(contact)
        if (wanted.isBlank()) return null

        // ContactVault reads the full on-device address book and maintains an
        // AES-GCM encrypted snapshot using a non-exportable Android Keystore key.
        // Names/numbers never go to Groq or into the GitHub source tree.
        val matches = ContactVault.load(this).mapNotNull { entry ->
            val number = normalizeWhatsAppNumber(entry.number, "") ?: return@mapNotNull null
            if (contactMatchScore(wanted, entry.name) >= 65) entry.name to number else null
        }

        val ranked = matches.distinctBy { it.second }
            .map { it to contactMatchScore(wanted, it.first) }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        // Never guess when two different numbers are similarly plausible.
        if (ranked.size > 1 && best.second - ranked[1].second < 8) return null
        return best.first.second
    }

    private fun openWhatsAppDeepLink(contact: String, message: String?): Boolean {
        val number = resolveWhatsAppNumber(contact) ?: return false
        val digits = number.trimStart('0')
        if (digits.isBlank()) return false
        val query = message?.takeIf { it.isNotBlank() }?.let { "?text=" + Uri.encode(it) }.orEmpty()
        val url = Uri.parse("https://wa.me/$digits$query")
        val intent = Intent(Intent.ACTION_VIEW, url).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageManager.getLaunchIntentForPackage("com.whatsapp") != null) setPackage("com.whatsapp")
            else if (packageManager.getLaunchIntentForPackage("com.whatsapp.w4b") != null) setPackage("com.whatsapp.w4b")
        }
        return UltronAccessibilityService.openExternalIntent(intent) || openActivity(intent)
    }

    private fun openWhatsAppContact(contact: String, message: String?): Boolean {
        if (contact.isBlank()) return false
        val keyguard = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val resolvedNumber = resolveWhatsAppNumber(contact)
        if (keyguard.isKeyguardLocked && resolvedNumber != null) {
            val digits = resolvedNumber.trimStart('0')
            val query = message?.takeIf { it.isNotBlank() }?.let { "?text=" + Uri.encode(it) }.orEmpty()
            val chatIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/\$digits\$query")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageManager.getLaunchIntentForPackage("com.whatsapp") != null) setPackage("com.whatsapp")
                else if (packageManager.getLaunchIntentForPackage("com.whatsapp.w4b") != null) setPackage("com.whatsapp.w4b")
            }
            val pending = PendingIntent.getActivity(
                this, 7402, chatIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notice = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Unlock to open WhatsApp chat")
                .setContentText("Tap after unlocking to open ${contact}'s conversation directly.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            getSystemService(NotificationManager::class.java).notify(741, notice)
            val response = "Your phone is locked. Tap the ULTRON notification after unlocking to open $contact's chat."
            updateNotification(response)
            speakResponse(response)
            return true
        }
        // Deep links only open chats. For an explicit send request, route
        // through the accessibility flow so ULTRON can fill and tap Send.
        if (!keyguard.isKeyguardLocked && resolvedNumber != null &&
            message.isNullOrBlank() && openWhatsAppDeepLink(contact, null)) {
            val response = "Opening your WhatsApp chat with $contact."
            updateNotification(response)
            speakResponse(response)
            return true
        }
        if (keyguard.isKeyguardLocked) {
            val resumeIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("ultron_whatsapp_contact", contact)
                putExtra("ultron_whatsapp_message", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                this, 7401, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notice = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Unlock to continue in WhatsApp")
                .setContentText("Tap after unlocking to open $contact's conversation.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            getSystemService(NotificationManager::class.java).notify(741, notice)
            updateNotification("Unlock your phone and tap the ULTRON notification to continue.")
            speakResponse("Your phone is locked. Unlock it and tap the ULTRON notification to continue with $contact.")
            return true
        }
        if (!UltronAccessibilityService.isEnabled()) {
            val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            if (launch != null) openActivity(launch)
            val response = "I opened WhatsApp, but contact navigation needs ULTRON Phone Control enabled in Android Accessibility settings."
            updateNotification(response)
            speakResponse(response)
            return true
        }
        val started = UltronAccessibilityService.openWhatsAppConversation(contact, message)
        val response = if (started) {
            if (message.isNullOrBlank()) "Finding $contact in WhatsApp."
            else "Sending your WhatsApp message to $contact."
        } else "I couldn't start WhatsApp contact navigation. Make sure WhatsApp is installed and ULTRON Phone Control is enabled."
        updateNotification(response)
        speakResponse(response)
        return true
    }

    private fun extractWhatsAppMessage(command: String): String? {
        val patterns = listOf(
            Regex("^(?:send|write|text|message) (?:a )?(?:whatsapp )?(?:message )?(.+)$"),
            Regex("^whatsapp (?:message )?(.+)$"),
            Regex("^send (.+) on whatsapp$")
        )
        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val message = match.groupValues[1].trim()
                .removeSuffix(" to my contact")
                .removeSuffix(" to that number")
                .trim()
            if (message.isNotBlank() && message !in listOf("hi", "hello")) return message
        }
        return null
    }

    private fun openWhatsAppChat(message: String?) {
        val encoded = message?.let { "?text=" + Uri.encode(it) }.orEmpty()
        val url = Uri.parse("https://wa.me/" + WHATSAPP_NUMBER + encoded)
        val whatsappIntent = Intent(Intent.ACTION_VIEW, url).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(whatsappIntent)
            val response = if (message.isNullOrBlank()) {
                "Opening your WhatsApp chat. Tell me what message to prepare."
            } else {
                "I've prepared that message in WhatsApp. Review it and tap Send."
            }
            updateNotification(response)
            speakResponse(response)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(browserIntent)
                val response = "WhatsApp isn't available to open directly. I opened the chat link in your browser."
                updateNotification(response)
                speakResponse(response)
            } catch (_: Exception) {
                val response = "I couldn't open WhatsApp. Check that it's installed."
                updateNotification(response)
                speakResponse(response)
            }
        } catch (_: Exception) {
            val response = "I couldn't open that WhatsApp chat."
            updateNotification(response)
            speakResponse(response)
        }
    }

    private fun launchAppPackage(packageName: String, fallbackIntent: Intent): Boolean {
        launchQueuedForUnlock = false
        val keyguard = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (keyguard.isKeyguardLocked) return openActivity(fallbackIntent)
        // Android blocks ordinary background services from launching activities.
        // The user-enabled accessibility bridge is the supported phone-wide launcher.
        if (UltronAccessibilityService.launchInstalledApp(this, packageName)) return true
        if (!UltronAccessibilityService.isEnabled()) {
            val settingsIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val pending = PendingIntent.getActivity(
                this, 7411, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notice = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Enable ULTRON phone control")
                .setContentText("Turn on ULTRON Phone Control once to launch apps by voice from anywhere.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            getSystemService(NotificationManager::class.java).notify(743, notice)
            updateNotification("Enable ULTRON Phone Control in Accessibility settings to open apps by voice outside ULTRON.")
            speakResponse("To open apps by voice from anywhere, enable ULTRON Phone Control in Accessibility settings once.")
            return false
        }
        return openActivity(fallbackIntent)
    }

    private fun openActivity(intent: Intent): Boolean {
        launchQueuedForUnlock = false
        val keyguard = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (keyguard.isKeyguardLocked) {
            launchQueuedForUnlock = true
            return try {
                val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = PendingIntent.getActivity(
                    this, 7410, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notice = Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Unlock to open app")
                    .setContentText("Tap to continue to the requested app.")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build()
                getSystemService(NotificationManager::class.java).notify(742, notice)
                updateNotification("Phone locked — tap the ULTRON notification after unlocking.")
                true
            } catch (_: Exception) {
                updateNotification("Unlock your phone to open that app.")
                false
            }
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Exception) {
            updateNotification("Android blocked opening that app")
            speakResponse("Android blocked that action.")
            false
        }
    }

    // Prevent the recognizer from hearing ULTRON's own spoken reply as a new command.
    private fun speakResponse(text: String) {
        voicePauseUntil = android.os.SystemClock.elapsedRealtime() +
            maxOf(2500L, text.length * 85L)
        UltronVoice.speak(this, text)
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: RuntimeException) { }
        wakeLock = null
    }

    override fun onDestroy() {
        active = false
        recognitionGeneration += 1
        restartHandler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: RuntimeException) { }
        try { recognizer?.destroy() } catch (_: RuntimeException) { }
        recognizer = null
        try { UltronVoice.stop() } catch (_: RuntimeException) { }
        releaseWakeLock()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: RuntimeException) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
