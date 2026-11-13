package com.ultron.assistant

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo

class MainActivity : Activity() {

    private val cyan = Color.rgb(0, 220, 255)
    private lateinit var feed: TextView
    private lateinit var status: TextView
    private lateinit var micButton: Button
    private var speechRecognizer: SpeechRecognizer? = null

    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
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
            textSize = 32f
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
            text = "SYSTEM ONLINE"
            textSize = 14f
            setTextColor(cyan)
            gravity = Gravity.CENTER
            setPadding(8, 24, 8, 12)
        }

        micButton = Button(this).apply {
            text = "🎤 TAP TO SPEAK"
            textSize = 18f
            setOnClickListener {
                startListening()
            }
        }

        val notifications = Button(this).apply {
            text = "ENABLE WHATSAPP ACCESS"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            }
        }

        val apps = Button(this).apply {
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
        layout.addView(micButton, buttonParams())
        layout.addView(notifications, buttonParams())
        layout.addView(apps, buttonParams())
        layout.addView(feedTitle)
        layout.addView(feed)

        setContentView(layout)
    }

    private fun startListening() {
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
                    status.text = "LISTENING..."
                    micButton.text = "🎤 LISTENING..."
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    micButton.text = "🎤 PROCESSING..."
                }

                override fun onError(error: Int) {
                    status.text = "Didn't catch that. Try again."
                    micButton.text = "🎤 TAP TO SPEAK"
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    val command = matches?.firstOrNull()

                    if (command != null) {
                        status.text = "HEARD: $command"
                        handleCommand(command)
                    } else {
                        status.text = "No command detected"
                    }

                    micButton.text = "🎤 TAP TO SPEAK"
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
        )

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        speechRecognizer?.startListening(intent)
    }

    private fun handleCommand(command: String) {
        val spoken = command.lowercase()

        val apps = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm"
        )

        if (spoken.contains("camera")) {
            try {
                startActivity(
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                )
            } catch (e: Exception) {
                status.text = "Camera unavailable"
            }
            return
        }

        if (spoken.contains("settings")) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            return
        }

        for ((name, packageName) in apps) {
            if (spoken.contains("open $name") ||
                spoken == name ||
                spoken.contains("$name please")) {

                val launchIntent =
                    packageManager.getLaunchIntentForPackage(packageName)

                if (launchIntent != null) {
                    startActivity(launchIntent)
                    status.text = "LAUNCHING $name"
                } else {
                    status.text = "$name is not installed"
                }
                return
            }
        }

        status.text = "Command not recognized: $command"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )

        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            status.text = "Microphone permission required"
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
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
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
