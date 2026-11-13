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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

class MainActivity : Activity() {

    private val cyan = Color.rgb(0, 220, 255)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var feed: TextView

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
        layout.addView(feedTitle)
        layout.addView(feed)

        setContentView(layout)

        requestNeededPermissions()
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
