
package com.ultron.assistant

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper

class MainActivity : Activity() {

    private val cyan = Color.rgb(0, 220, 255)
    private lateinit var feed: TextView
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

        val notifications = Button(this).apply {
            text = "ENABLE WHATSAPP ACCESS"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val apps = Button(this).apply {
            text = "OPEN INSTALLED APPS"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                startActivity(Intent.createChooser(intent, "Choose an app"))
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
        layout.addView(notifications, buttonParams())
        layout.addView(apps, buttonParams())
        layout.addView(feedTitle)
        layout.addView(feed)

        setContentView(layout)
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

    private fun buttonParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 16, 0, 0)
        }
}
