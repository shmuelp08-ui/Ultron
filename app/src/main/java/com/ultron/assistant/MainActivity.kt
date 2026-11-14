package com.ultron.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private val cyan = Color.rgb(36, 205, 255)
    private val cyanSoft = Color.rgb(110, 225, 255)
    private val bg = Color.rgb(3, 9, 19)
    private val panel = Color.rgb(7, 22, 38)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var statusText: TextView
    private lateinit var weatherText: TextView
    private lateinit var systemText: TextView
    private lateinit var notificationText: TextView
    private lateinit var agendaText: TextView
    private lateinit var musicText: TextView
    private lateinit var chatLog: TextView
    private lateinit var chatInput: EditText
    private var chatBusy = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                clockText.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
                dateText.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
                refreshSystem()
                refreshNotifications()
                refreshAgenda()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildDashboard()
        requestRuntimePermissions()
        refreshWeather()
    }

    private fun buildDashboard() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        val top = row()
        val brand = text("U L T R O N", 24f, cyan, true)
        brand.letterSpacing = .18f
        top.addView(brand, weightParams())
        val live = text("● SYSTEM ONLINE", 10f, cyanSoft, true).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(live)
        root.addView(top)

        val timePanel = panelCard()
        val timeRow = row()
        val timeCol = column()
        clockText = text("00:00", 58f, cyan, true)
        clockText.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        dateText = text("INITIALIZING", 13f, cyanSoft, true)
        timeCol.addView(clockText)
        timeCol.addView(dateText)
        timeRow.addView(timeCol, weightParams())
        val orb = CoreView(this)
        timeRow.addView(orb, LinearLayout.LayoutParams(dp(112), dp(112)))
        timePanel.addView(timeRow)
        root.addView(timePanel)

        val two = row()
        val weather = panelCard()
        weather.addView(sectionTitle("◉  LOCAL WEATHER"))
        weatherText = text("Set a city to load live weather", 14f, Color.WHITE)
        weather.addView(weatherText)
        weather.addView(actionButton("SET LOCATION") { chooseWeatherCity() }, buttonParams())
        two.addView(weather, weightParams(1f))
        val system = panelCard()
        system.addView(sectionTitle("▤  SYSTEM STATUS"))
        systemText = text("Reading device stats…", 13f, Color.WHITE)
        system.addView(systemText)
        two.addView(system, weightParams(1f))
        root.addView(two)

        val quick = panelCard()
        quick.addView(sectionTitle("⌘  QUICK LAUNCH"))
        val apps = installedApps().take(8)
        val appGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps.chunked(4).forEach { group ->
            val r = row()
            group.forEach { app ->
                val b = actionButton("▣\n$app") { launchApp(app) }
                r.addView(b, weightParams(1f))
            }
            appGrid.addView(r)
        }
        if (apps.isEmpty()) appGrid.addView(text("No launchable apps found", 13f, Color.WHITE))
        quick.addView(appGrid)
        root.addView(quick)

        val side = row()
        val notifications = panelCard()
        notifications.addView(sectionTitle("◉  NOTIFICATIONS"))
        notificationText = text("Waiting for notification access…", 12f, Color.WHITE)
        notifications.addView(notificationText)
        notifications.addView(actionButton("NOTIFICATION ACCESS") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, buttonParams())
        side.addView(notifications, weightParams(1f))
        val agenda = panelCard()
        agenda.addView(sectionTitle("▦  UP NEXT"))
        agendaText = text("Calendar permission needed", 12f, Color.WHITE)
        agenda.addView(agendaText)
        agenda.addView(actionButton("OPEN CALENDAR") {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
        }, buttonParams())
        side.addView(agenda, weightParams(1f))
        root.addView(side)

        val music = panelCard()
        music.addView(sectionTitle("♫  MEDIA CONTROL"))
        musicText = text("Control your current media session", 13f, Color.WHITE)
        music.addView(musicText)
        val mediaRow = row()
        mediaRow.addView(actionButton("⏮") { mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, weightParams(1f))
        mediaRow.addView(actionButton("▶ / ❚❚") { mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, weightParams(1f))
        mediaRow.addView(actionButton("⏭") { mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }, weightParams(1f))
        music.addView(mediaRow)
        root.addView(music)

        val chat = panelCard()
        chat.addView(sectionTitle("◉  TALK TO ULTRON"))
        chatLog = text("Your assistant is ready. Configure your AI key in SETTINGS to enable online intelligence.", 13f, Color.WHITE)
        chat.addView(chatLog)
        chatInput = EditText(this).apply {
            hint = "Ask ULTRON anything…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(false)
            minLines = 1
            maxLines = 3
            setBackground(panelDrawable())
        }
        chat.addView(chatInput, buttonParams())
        chat.addView(actionButton("SEND MESSAGE") { sendToAI() }, buttonParams())
        root.addView(chat)

        val bottom = row()
        bottom.addView(actionButton("▦ DASHBOARD") { scrollToTop() }, weightParams(1f))
        bottom.addView(actionButton("🎙 VOICE") { toggleVoice() }, weightParams(1f))
        bottom.addView(actionButton("⚙ SETTINGS") { showSettings() }, weightParams(1f))
        root.addView(bottom)
    }

    private fun scrollToTop() { (root.parent as? ScrollView)?.smoothScrollTo(0, 0) }

    private fun requestRuntimePermissions() {
        val ask = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.READ_CALENDAR)
        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 77)
        else startVoiceIfEnabled()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 77) {
            refreshAgenda()
            startVoiceIfEnabled()
        }
    }

    private fun startVoiceIfEnabled() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            UltronSettings.get(this, "voice_enabled") != "false") {
            try { startForegroundService(Intent(this, VoiceWakeService::class.java)) } catch (_: Exception) { }
        }
    }

    private fun toggleVoice() {
        val enabled = UltronSettings.get(this, "voice_enabled") != "false"
        if (enabled) {
            UltronSettings.save(this, "voice_enabled", "false")
            startService(Intent(this, VoiceWakeService::class.java).setAction(VoiceWakeService.ACTION_STOP))
            statusTextOrToast("Voice listening paused")
        } else {
            UltronSettings.save(this, "voice_enabled", "true")
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceIfEnabled()
            else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77)
            statusTextOrToast("Voice listening starting")
        }
    }

    private fun statusTextOrToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun refreshSystem() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes / (1024L * 1024L * 1024L)
            val free = stat.availableBytes / (1024L * 1024L * 1024L)
            val runtime = Runtime.getRuntime()
            val usedRam = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
            val maxRam = runtime.maxMemory() / (1024L * 1024L)
            systemText.text = "BATTERY   $battery%\nSTORAGE   ${total - free} / $total GB\nAPP RAM   $usedRam / $maxRam MB"
        } catch (_: Exception) { systemText.text = "System stats unavailable" }
    }

    private fun refreshNotifications() {
        val items = synchronized(WhatsAppNotificationListener.recentNotifications) {
            WhatsAppNotificationListener.recentNotifications.take(4).toList()
        }
        notificationText.text = if (items.isEmpty()) "No recent WhatsApp alerts.\nEnable notification access to populate this panel."
            else items.joinToString("\n\n")
    }

    private fun refreshAgenda() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            agendaText.text = "Allow calendar access to show upcoming events"
            return
        }
        try {
            val now = System.currentTimeMillis()
            val end = now + 7L * 24 * 60 * 60 * 1000
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(now.toString()).appendPath(end.toString()).build()
            contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                "${CalendarContract.Instances.BEGIN} >= ?", arrayOf(now.toString()), CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                val lines = mutableListOf<String>()
                while (c.moveToNext() && lines.size < 3) {
                    val title = c.getString(0) ?: "Event"
                    val begin = c.getLong(1)
                    val whenText = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(begin))
                    lines.add("$whenText\n$title")
                }
                agendaText.text = if (lines.isEmpty()) "No upcoming events in the next 7 days" else lines.joinToString("\n\n")
            }
        } catch (_: Exception) { agendaText.text = "Calendar unavailable" }
    }

    private fun chooseWeatherCity() {
        val input = EditText(this).apply {
            hint = "City (e.g. Beit Shemesh)"
            setText(UltronSettings.get(this@MainActivity, "weather_city") ?: "")
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this).setTitle("Weather location").setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val city = input.text.toString().trim()
                if (city.isNotBlank()) {
                    UltronSettings.save(this, "weather_city", city)
                    refreshWeather()
                }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun refreshWeather() {
        val city = UltronSettings.get(this, "weather_city")
        if (city.isNullOrBlank()) { weatherText.text = "Tap SET LOCATION to choose your city"; return }
        weatherText.text = "Loading weather for $city…"
        Thread {
            try {
                val geo = JSONObject(URL("https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(city)}&count=1&language=en&format=json").readText())
                val place = geo.optJSONArray("results")?.optJSONObject(0) ?: throw Exception("City not found")
                val lat = place.getDouble("latitude")
                val lon = place.getDouble("longitude")
                val forecast = JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto").readText())
                val current = forecast.getJSONObject("current")
                val daily = forecast.getJSONObject("daily")
                val label = "${current.getDouble("temperature_2m").toInt()}°  ${weatherDescription(current.getInt("weather_code"))}\n${place.optString("name", city)}  •  H ${daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()}° / L ${daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()}°"
                runOnUiThread { weatherText.text = label }
            } catch (e: Exception) {
                runOnUiThread { weatherText.text = "Weather unavailable. Check internet or city name." }
            }
        }.start()
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"; 61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"; 80, 81, 82 -> "Showers"; 95, 96, 99 -> "Thunderstorm"
        else -> "Current conditions"
    }

    private fun installedApps(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.loadLabel(packageManager)?.toString() }
                .distinct().sorted().take(8)
        } catch (_: Exception) { emptyList() }
    }

    private fun launchApp(label: String) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(intent, 0).firstOrNull {
            it.loadLabel(packageManager)?.toString() == label
        }
        val launch = match?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launch != null) startActivity(launch) else statusTextOrToast("Couldn't open $label")
    }

    private fun mediaKey(keyCode: Int) {
        try {
            val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
            sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, down))
            sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, up))
        } catch (_: Exception) { statusTextOrToast("Media control unavailable") }
    }

    private fun sendToAI() {
        val question = chatInput.text.toString().trim()
        if (question.isBlank() || chatBusy) return
        val key = UltronSettings.get(this, "openai_api_key")
        if (key.isNullOrBlank()) { chatLog.text = "Add your Groq API key in SETTINGS first."; return }
        chatBusy = true
        chatInput.setText("")
        chatLog.text = "YOU: $question\n\nULTRON: Thinking…"
        UltronConversation.addTurn(this, "User", question)
        Thread {
            val answer = AIController.ask(key, question, UltronMemory.recall(this), UltronConversation.transcript(this))
            UltronConversation.addTurn(this, "ULTRON", answer)
            runOnUiThread {
                chatLog.text = "YOU: $question\n\nULTRON: $answer"
                chatBusy = false
                UltronVoice.speak(this, answer)
            }
        }.start()
    }

    private fun showSettings() {
        val options = arrayOf("AI / Groq API key", "Fish Audio voice", "WhatsApp notifications", "Calendar permission", "Weather location", "Saved memories")
        AlertDialog.Builder(this).setTitle("ULTRON SETTINGS").setItems(options) { _, which ->
            when (which) {
                0 -> credentialDialog("Groq API key", "openai_api_key")
                1 -> fishDialog()
                2 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                3 -> requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 77)
                4 -> chooseWeatherCity()
                5 -> showMemories()
            }
        }.show()
    }

    private fun credentialDialog(title: String, key: String) {
        val field = EditText(this).apply { hint = title; setTextColor(Color.WHITE); inputType = 129 }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setPositiveButton("SAVE") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotBlank()) UltronSettings.save(this, key, value)
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun fishDialog() {
        val box = column()
        val api = EditText(this).apply { hint = "Fish Audio API key"; setTextColor(Color.WHITE); inputType = 129 }
        val voice = EditText(this).apply { hint = "Voice model ID"; setTextColor(Color.WHITE) }
        box.addView(api); box.addView(voice)
        AlertDialog.Builder(this).setTitle("FISH AUDIO").setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                if (api.text.isNotBlank()) UltronSettings.save(this, "fish_api_key", api.text.toString().trim())
                if (voice.text.isNotBlank()) UltronSettings.save(this, "fish_voice_id", voice.text.toString().trim())
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showMemories() {
        val memories = UltronMemory.recall(this)
        AlertDialog.Builder(this).setTitle("SAVED MEMORY")
            .setMessage(if (memories.isEmpty()) "No saved memories yet." else memories.takeLast(30).joinToString("\n\n"))
            .setPositiveButton("CLOSE", null).show()
    }

    private fun panelCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(12), dp(13), dp(12))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(8, 29, 48), Color.rgb(4, 13, 26))).apply {
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), Color.rgb(18, 125, 177))
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), dp(5), dp(3), dp(5))
        }
    }

    private fun panelDrawable() = GradientDrawable().apply {
        setColor(Color.rgb(5, 17, 29)); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Color.rgb(20, 116, 160))
    }
    private fun sectionTitle(value: String) = text(value, 12f, cyan, true).apply { setPadding(0, 0, 0, dp(9)) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun weightParams(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    private fun buttonParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(5), 0, dp(3)) }
    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f; setTextColor(cyanSoft); isAllCaps = false
        background = GradientDrawable().apply { setColor(Color.rgb(5, 24, 40)); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Color.rgb(15, 130, 184)) }
        setPadding(dp(5), dp(7), dp(5), dp(7)); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::clockText.isInitialized) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            refreshNotifications()
            refreshAgenda()
            startVoiceIfEnabled()
        }
    }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private class CoreView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f; val radius = max(1f, width.coerceAtMost(height) * .36f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
            paint.color = Color.rgb(25, 180, 245)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.strokeWidth = 2.5f
            canvas.drawArc(cx-radius-7, cy-radius-7, cx+radius+7, cy+radius+7, phase, 235f, false, paint)
            paint.color = Color.rgb(40, 220, 255); paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius * .62f, paint.apply { alpha = 28 })
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
            for (i in 0..5) {
                val a = Math.toRadians((phase + i * 60).toDouble())
                val x = cx + kotlin.math.cos(a).toFloat() * radius * .83f
                val y = cy + kotlin.math.sin(a).toFloat() * radius * .83f
                canvas.drawCircle(x, y, 2.8f, paint)
            }
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = 11f
            canvas.drawText("AI CORE", cx, cy + 4f, paint)
            phase = (phase + 2.2f) % 360f
            postInvalidateDelayed(50)
        }
    }
}
