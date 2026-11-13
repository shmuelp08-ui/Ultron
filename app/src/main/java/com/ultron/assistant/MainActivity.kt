package com.ultron.assistant

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import android.widget.ScrollView
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

class MainActivity : Activity() {

    private val cyan = Color.rgb(0, 220, 255)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var feed: TextView
    private lateinit var chatHistory: TextView
    private lateinit var messageInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var fishApiKeyInput: EditText
    private lateinit var fishVoiceIdInput: EditText
    private var cloneAudioUri: android.net.Uri? = null
    private lateinit var cloneTranscriptInput: EditText
    private lateinit var cloneOutputInput: EditText
    private lateinit var cloneStatus: TextView

    private val refreshFeed = object : Runnable {
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
            text = "VOICE SYSTEM INITIALIZING..."
            textSize = 15f
            setTextColor(cyan)
            gravity = Gravity.CENTER
            setPadding(8, 30, 8, 25)
        }

        val startButton = Button(this).apply {
            text = "START VOICE LISTENING"
            setOnClickListener {
                startVoiceService()
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP VOICE LISTENING"
            setOnClickListener {
                val intent = Intent(
                    this@MainActivity,
                    VoiceWakeService::class.java
                ).apply {
                    action = VoiceWakeService.ACTION_STOP
                }
                startService(intent)
                status.text = "VOICE SYSTEM STOPPED"
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
        layout.addView(startButton, buttonParams())
        layout.addView(stopButton, buttonParams())
        layout.addView(notifications, buttonParams())
        layout.addView(appsButton, buttonParams())

        val chatTitle = TextView(this).apply {
            text = "\nULTRON AI CHAT"
            textSize = 18f
            setTextColor(cyan)
            setPadding(0, 24, 0, 8)
        }
        apiKeyInput = EditText(this).apply {
            hint = if (UltronSettings.get(this@MainActivity, "openai_api_key").isNullOrBlank())
                "Paste your free Groq API key" else "Groq key saved — paste a new one to replace"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val saveKeyButton = Button(this).apply {
            text = "SAVE GROQ KEY"
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                if (key.startsWith("gsk_")) {
                    UltronSettings.save(this@MainActivity, "openai_api_key", key)
                    apiKeyInput.setText("")
                    apiKeyInput.hint = "API key saved"
                    chatHistory.append("\nSYSTEM: AI key saved on this phone.")
                } else {
                    chatHistory.append("\nSYSTEM: Paste a valid-looking API key first.")
                }
            }
        }
        chatHistory = TextView(this).apply {
            text = "ULTRON AI online when an API key is configured."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(8, 16, 8, 16)
        }
        messageInput = EditText(this).apply {
            hint = "Message ULTRON..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val sendButton = Button(this).apply {
            text = "SEND TO ULTRON"
            setOnClickListener { sendToAI() }
        }

        val fishTitle = TextView(this).apply {
            text = "\nFISH AUDIO VOICE"
            textSize = 18f
            setTextColor(cyan)
            setPadding(0, 24, 0, 8)
        }
        fishApiKeyInput = EditText(this).apply {
            hint = if (UltronSettings.get(this@MainActivity, "fish_api_key").isNullOrBlank())
                "Fish Audio API key" else "Fish Audio key saved"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        fishVoiceIdInput = EditText(this).apply {
            hint = if (UltronSettings.get(this@MainActivity, "fish_voice_id").isNullOrBlank())
                "Fish Audio voice model ID" else "Voice ID saved — enter to replace"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val saveFishButton = Button(this).apply {
            text = "SAVE FISH AUDIO VOICE"
            setOnClickListener {
                val key = fishApiKeyInput.text.toString().trim()
                val voiceId = fishVoiceIdInput.text.toString().trim()
                if (key.isNotBlank()) UltronSettings.save(this@MainActivity, "fish_api_key", key)
                if (voiceId.isNotBlank()) UltronSettings.save(this@MainActivity, "fish_voice_id", voiceId)
                fishApiKeyInput.setText("")
                fishVoiceIdInput.setText("")
                fishApiKeyInput.hint = "Fish Audio key saved"
                fishVoiceIdInput.hint = "Voice ID saved"
                chatHistory.append("\nSYSTEM: Fish Audio voice settings saved.")
            }
        }


        val cloneTitle = TextView(this).apply {
            text = "\nOFFLINE VOICE CLONING"
            textSize = 18f
            setTextColor(cyan)
            setPadding(0, 28, 0, 8)
        }
        val chooseAudioButton = Button(this).apply {
            text = "CHOOSE VOICE RECORDING"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }
                startActivityForResult(pick, 4201)
            }
        }
        cloneTranscriptInput = EditText(this).apply {
            hint = "Exact transcript of the recording"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        cloneOutputInput = EditText(this).apply {
            hint = "What ULTRON should say in that voice"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        cloneStatus = TextView(this).apply {
            text = "Choose a clean recording, then enter its exact transcript."
            textSize = 13f
            setTextColor(cyan)
            setPadding(8, 12, 8, 12)
        }
        val cloneButton = Button(this).apply {
            text = "CLONE VOICE & SPEAK"
            setOnClickListener {
                val uri = cloneAudioUri
                if (uri == null) {
                    cloneStatus.text = "Choose a voice recording first."
                } else {
                    cloneStatus.text = "Starting…"
                    VoiceCloneEngine.cloneAndPlay(
                        this@MainActivity, uri,
                        cloneTranscriptInput.text.toString(),
                        cloneOutputInput.text.toString()
                    ) { message -> handler.post { cloneStatus.text = message } }
                }
            }
        }
        layout.addView(cloneTitle)
        layout.addView(chooseAudioButton, buttonParams())
        layout.addView(cloneTranscriptInput, buttonParams())
        layout.addView(cloneOutputInput, buttonParams())
        layout.addView(cloneButton, buttonParams())
        layout.addView(cloneStatus, buttonParams())

        layout.addView(chatTitle)
        layout.addView(apiKeyInput, buttonParams())
        layout.addView(saveKeyButton, buttonParams())
        layout.addView(fishTitle)
        layout.addView(fishApiKeyInput, buttonParams())
        layout.addView(fishVoiceIdInput, buttonParams())
        layout.addView(saveFishButton, buttonParams())
        layout.addView(chatHistory, buttonParams())
        layout.addView(messageInput, buttonParams())
        layout.addView(sendButton, buttonParams())
        layout.addView(feedTitle)
        layout.addView(feed)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        setContentView(scroll)

        requestNeededPermissions()
    }


    @Deprecated("Legacy picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4201 && resultCode == RESULT_OK) {
            cloneAudioUri = data?.data
            cloneAudioUri?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                cloneStatus.text = "Recording selected ✓"
            }
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 100)
        } else {
            startVoiceService()
        }
    }

    private fun startVoiceService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            status.text = "MICROPHONE PERMISSION REQUIRED"
            requestNeededPermissions()
            return
        }

        try {
            val intent = Intent(this, VoiceWakeService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            status.text = "BACKGROUND VOICE SYSTEM STARTED"
        } catch (e: Exception) {
            status.text = "COULD NOT START VOICE SYSTEM"
        }
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
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceService()
        } else {
            status.text = "MICROPHONE PERMISSION REQUIRED"
        }
    }

    private fun sendToAI() {
        val question = messageInput.text.toString().trim()
        if (question.isBlank()) return
        val key = UltronSettings.get(this, "openai_api_key")
        if (key.isNullOrBlank()) {
            chatHistory.append("\nYOU: $question\nULTRON: Add your Groq API key above first.")
            return
        }
        messageInput.setText("")
        chatHistory.append("\nYOU: $question\nULTRON: Thinking...")
        Thread {
            val answer = AIController.ask(key, question, UltronMemory.recall(this))
            handler.post {
                val current = chatHistory.text.toString()
                chatHistory.text = current.substringBeforeLast("ULTRON: Thinking...") + "ULTRON: $answer"
                UltronVoice.speak(this, answer)
            }
        }.start()
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

        if (::feed.isInitialized) {
            handler.removeCallbacks(refreshFeed)
            handler.post(refreshFeed)
        }

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceService()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refreshFeed)
        super.onPause()
    }

    override fun onDestroy() {
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
