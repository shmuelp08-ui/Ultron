
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

class MainActivity : Activity() {

    private val cyan = Color.rgb(0, 220, 255)

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

        val status = TextView(this).apply {
            text = "\nSYSTEM ONLINE\n\nANDROID COMPANION INITIALIZED"
            textSize = 15f
            setTextColor(cyan)
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

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(status)

        layout.addView(notifications, buttonParams())
        layout.addView(apps, buttonParams())

        setContentView(layout)
    }

    private fun buttonParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 16, 0, 0)
        }
}
