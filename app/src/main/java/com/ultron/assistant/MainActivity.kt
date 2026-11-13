package com.ultron.assistant

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.provider.Settings
import android.provider.MediaStore
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.os.Build

class MainActivity : Activity() {

    private val cyan = Color.rgb(0, 220, 255)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var feed: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var active = false
    private var waitingForCommand = false

    private val apps = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "gmail" to "com.google.android.gm"
    )

    private val restartListening = Runnable {
        if (active) {
            listen()
        }
    }

    private val refreshFeed = object : Runnable {
        override fun run() {
            if (!active) return
            updateFeed()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(5, 8, 15))
        }

        val title = TextView(this).apply {
            text = "U L T R O N"
            textSize = 34f
            setTextColor(cyan)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "PERSONAL AI SYSTEM"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "INITIALIZING VOICE SYSTEM..."
            textSize = 15f
            setTextColor(cyan)
            gravity = Gravity.CENTER
            setPadding(8, 30, 8, 25)
        }

        val notifications = Button(this).apply {
            text = "ENABLE WHATSAPP ACCESS"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            }
        }

        val appsButton = Button(this).apply {
            text = "OPEN INSTALLED APPS"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                startActivity(
                    Intent.createChooser(intent, "Choose an app")
                )
            }
        }

        val feedTitle = TextView(this).apply {
            text = "\nWHATSAPP NOTIFICATION FEED"
            textSize = 16f
            setTextColor(cyan)
        }

        feed = TextView(this).apply {
            text = "Waiting for notifications..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(8, 16, 8, 16)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(status)
        layout.addView(notifications, buttonParams())
        layout.addView(appsButton, buttonParams())
        layout.addView(feedTitle)
        layout.addView(feed)

        setContentView(layout)
    }

    private fun startVoiceSystem() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition unavailable"
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }

        speechRecognizer?.destroy()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    if (active) {
                        status.text = if (waitingForCommand) {
                            "COMMAND MODE — SPEAK NOW"
                        } else {
                            "LISTENING FOR ULTRON..."
                        }
                    }
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    status.text = "PROCESSING VOICE..."
                }

                override fun onError(error: Int) {
                    if (active) {
                        handler.removeCallbacks(restartListening)
                        handler.postDelayed(restartListening, 1000)
                    }
                }

                override fun onResults(results: Bundle?) {

                    if (!active) return

                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    val spoken = matches?.firstOrNull()
                        ?.trim()
                        ?.lowercase()
                        ?: ""

                    if (waitingForCommand) {

                        waitingForCommand = false

                        if (spoken.isNotBlank()) {
                            status.text = "COMMAND: $spoken"
                            handleCommand(spoken)
                        } else {
                            status.text = "NO COMMAND DETECTED"
                        }

                    } else {

                        val wakeIndex = spoken.indexOf("ultron")

                        if (wakeIndex >= 0) {

                            val remaining = spoken
                                .substring(wakeIndex + "ultron".length)
                                .trim()

                            if (remaining.isNotBlank()) {
                                status.text = "ULTRON ACTIVATED"
                                handleCommand(remaining)
                            } else {
                                waitingForCommand = true
                                status.text = "ULTRON ACTIVATED — SPEAK"
                            }

                        } else {
                            status.text = "WAITING FOR ULTRON..."
                        }
                    }

                    handler.removeCallbacks(restartListening)
                    handler.postDelayed(restartListening, 700)
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
        )

        active = true
        waitingForCommand = false

        status.text = "VOICE SYSTEM ONLINE"

        listen()
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

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "en-US"
            )

            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )

            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                3
            )
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            handler.postDelayed(restartListening, 1500)
        }
    }

    private fun handleCommand(command: String) {

        val spoken = command.lowercase().trim()

        if (spoken.contains("camera")) {
            try {
                startActivity(
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                )
                status.text = "OPENING CAMERA"
            } catch (e: Exception) {
                status.text = "CAMERA UNAVAILABLE"
            }
            return
        }

        if (spoken.contains("settings")) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            status.text = "OPENING SETTINGS"
            return
        }

        for ((name, packageName) in apps) {

            if (
                spoken.contains("open $name") ||
                spoken == name ||
                spoken.contains("$name please")
            ) {

                val launchIntent =
                    packageManager.getLaunchIntentForPackage(packageName)

                if (launchIntent != null) {
                    startActivity(launchIntent)
                    status.text = "LAUNCHING $name"
                } else {
                    status.text = "$name IS NOT INSTALLED"
                }

                return
            }
        }

        status.text = "UNKNOWN COMMAND: $command"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceSystem()
        } else {
            status.text = "MICROPHONE PERMISSION REQUIRED"
        }
    }

    private fun updateFeed() {

        val messages = synchronized(
            WhatsAppNotificationListener.recentNotifications
        ) {
            WhatsAppNotificationListener.recentNotifications.toList()
        }

        feed.text = if (messages.isEmpty()) {
            "No WhatsApp notifications received yet."
        } else {
            messages.joinToString("\n\n")
        }
    }

    override fun onResume() {
        super.onResume()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startVoiceSystem()
        }

        handler.post(refreshFeed)
    }

    override fun onPause() {
        active = false

        handler.removeCallbacks(restartListening)
        handler.removeCallbacks(refreshFeed)

        speechRecognizer?.cancel()

        super.onPause()
    }

    override fun onDestroy() {
        active = false
        speechRecognizer?.destroy()
        speechRecognizer = null

        handler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }

    private fun buttonParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 16, 0, 0)
        }
}
