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

class VoiceWakeService : Service() {

    companion object {
        const val ACTION_STOP = "com.ultron.assistant.STOP_VOICE"
        private const val CHANNEL_ID = "ultron_voice_channel"
        private const val NOTIFICATION_ID = 404
    }

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var waitingForCommand = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val restartHandler = android.os.Handler(mainLooper)
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
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            UltronSettings.save(this, "voice_enabled", "false")
            active = false
            restartHandler.removeCallbacksAndMessages(null)
            stopSelf()
            return START_NOT_STICKY
        }

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

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!active) {
            active = true
            acquireWakeLock()
            startRecognition()
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
            updateNotification("Speech recognition unavailable")
            return
        }

        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(
                applicationContext
            )

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        updateNotification(
                            if (waitingForCommand) {
                                "ULTRON activated — say your command"
                            } else {
                                "Listening for ULTRON..."
                            }
                        )
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        updateNotification("Processing...")
                    }

                    override fun onError(error: Int) {
                        restartListening(1200)
                    }

                    override fun onResults(results: Bundle?) {
                        if (!active) return

                        val spokenOptions = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.map { it.trim().lowercase() }
                            ?: emptyList()

                        processSpeech(spokenOptions)
                        restartListening(700)
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

            listen()

        } catch (_: Exception) {
            restartListening(2000)
        }
    }

    private fun listen() {
        if (!active) return

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
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
        if (spoken.matches(Regex(".*\\\\b(time is it|current time|tell me the time|what time|the time)\\\\b.*"))) {
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
            .replace(Regex("\\s+(please|for me)$"), "")
            .trim()

        if (appRequest.isBlank()) {
            updateNotification("Which app should I open?")
            UltronVoice.speak(this, "Which app should I open?")
            return
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installedApps = packageManager.queryIntentActivities(launcherIntent, 0)

        val match = installedApps
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString() ?: return@mapNotNull null
                val normalizedLabel = normalizeCommand(label)
                if (normalizedLabel.isBlank()) return@mapNotNull null
                Triple(info.activityInfo.packageName, normalizedLabel, label)
            }
            .distinctBy { it.first }
            .filter { (_, label, _) ->
                label == appRequest ||
                    appRequest.replace(" ", "") == label.replace(" ", "") ||
                    label.contains(appRequest) ||
                    appRequest.contains(label)
            }
            .minByOrNull { (_, label, _) ->
                when {
                    label == appRequest -> 0
                    label.replace(" ", "") == appRequest.replace(" ", "") -> 1
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

        UltronConversation.addTurn(this, "User", command)
        val recentConversation = UltronConversation.transcript(this)
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

    private fun openActivity(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            updateNotification("Android blocked opening that app")
            UltronVoice.speak(this, "Android blocked that action.")
        }
    }

    override fun onDestroy() {
        active = false
        restartHandler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        UltronVoice.stop()

        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }

        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
