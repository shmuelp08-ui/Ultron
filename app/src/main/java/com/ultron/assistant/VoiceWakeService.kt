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
    private var wakeLock: PowerManager.WakeLock? = null
    // Service Context is not attached during construction; initialize Handler in onCreate.
    private lateinit var restartHandler: android.os.Handler
    private val restartTask = Runnable { if (active) startRecognition() }

    private val apps = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "gmail" to "com.google.android.gm"
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

                        val spokenOptions = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.map { it.trim().lowercase() }
                            ?: emptyList()

                        processSpeech(spokenOptions)
                        // Give command handling/TTS a moment before reopening the recognizer.
                        restartListening(1400)
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}

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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
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
        return value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
            UltronVoice.speak(this, answer)
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
                UltronVoice.speak(this, answer)
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
            UltronVoice.speak(this, answer)
            return
        }

        // WhatsApp: open the user-supplied chat, or prepare a dictated message.
        // WhatsApp displays the draft; the user must review and tap Send.
        val whatsappMessage = extractWhatsAppMessage(spoken)
        if (whatsappMessage != null) {
            openWhatsAppChat(whatsappMessage)
            return
        }
        if (spoken.contains("whatsapp") || spoken.contains("what s app")) {
            openWhatsAppChat(null)
            return
        }

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
            UltronVoice.speak(this, message)
            return
        }

        if (spoken.contains("camera")) {
            openActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
            updateNotification("Opening camera")
            UltronVoice.speak(this, "Opening camera.")
            return
        }

        if (spoken.contains("settings")) {
            openActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            updateNotification("Opening settings")
            UltronVoice.speak(this, "Opening settings.")
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
                openActivity(aliasIntent)
                updateNotification("Opening " + appRequest)
                UltronVoice.speak(this, "Opening " + appRequest + ".")
            } else {
                updateNotification(appRequest + " is not installed or cannot be launched")
                UltronVoice.speak(this, appRequest + " is not available on this phone.")
            }
            return
        }

        if (appRequest.isBlank()) {
            updateNotification("Which app should I open?")
            UltronVoice.speak(this, "Which app should I open?")
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
                openActivity(launchIntent)
                updateNotification("Opening ${match.third}")
                UltronVoice.speak(this, "Opening ${match.third}.")
            } else {
                updateNotification("Can't launch ${match.third}")
            }
            return
        }

        // If it is not an app-launch request, treat it as a natural AI question.
        val apiKey = UltronSettings.get(this, "openai_api_key")
        if (apiKey.isNullOrBlank()) {
            updateNotification("AI key needed — open ULTRON app")
            UltronVoice.speak(this, "I can answer that, but first add your Groq API key in the ULTRON app.")
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
                    UltronVoice.speak(this, answer)
                }
            }
        }.start()
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
            UltronVoice.speak(this, response)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(browserIntent)
                val response = "WhatsApp isn't available to open directly. I opened the chat link in your browser."
                updateNotification(response)
                UltronVoice.speak(this, response)
            } catch (_: Exception) {
                val response = "I couldn't open WhatsApp. Check that it's installed."
                updateNotification(response)
                UltronVoice.speak(this, response)
            }
        } catch (_: Exception) {
            val response = "I couldn't open that WhatsApp chat."
            updateNotification(response)
            UltronVoice.speak(this, response)
        }
    }

    private fun openActivity(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            updateNotification("Android blocked opening that app")
            UltronVoice.speak(this, "Android blocked that action.")
        }
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
