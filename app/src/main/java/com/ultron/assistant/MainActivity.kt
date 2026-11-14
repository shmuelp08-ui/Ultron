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
import android.location.Geocoder
import android.location.LocationManager
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
import android.util.Log
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
                refreshVoiceStatus()
                refreshNotifications()
                refreshAgenda()
                refreshMediaStatus()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildDashboard()
        requestRuntimePermissions()
        refreshWeather()
        showPreviousCrashIfAny()
    }

    // Persist fatal launch/runtime crashes so the next launch can show the exact
    // exception instead of leaving us guessing from a successful compile.
    private fun installCrashReporter() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = Log.getStackTraceString(error)
                openFileOutput("ultron_last_crash.txt", MODE_PRIVATE).use {
                    it.write(report.toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) { }
            previousHandler?.uncaughtException(thread, error)
        }
    }

    private fun showPreviousCrashIfAny() {
        val file = java.io.File(filesDir, "ultron_last_crash.txt")
        if (!file.exists()) return
        val report = try { file.readText().take(12000) } catch (_: Exception) { "Crash report could not be read." }
        AlertDialog.Builder(this)
            .setTitle("ULTRON — LAST CRASH REPORT")
            .setMessage(report)
            .setPositiveButton("COPY & CLOSE") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ULTRON crash report", report))
                file.delete()
                statusTextOrToast("Crash report copied")
            }
            .setNegativeButton("CLOSE") { _, _ -> file.delete() }
            .setCancelable(false)
            .show()
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

        statusText = text("VOICE OFF", 11f, Color.GRAY, true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        root.addView(statusText)

        val timePanel = panelCard().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val timeRow = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        // Prominent animated holographic core: centered and large enough to be unmistakable.
        val orb = CoreView(this)
        timeRow.addView(orb, LinearLayout.LayoutParams(dp(196), dp(196)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(2)
        })
        val coreLabel = text("◈  NEURAL CORE  ◈", 10f, cyanSoft, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .22f
        }
        timeRow.addView(coreLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        clockText = text("00:00", 58f, cyan, true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        dateText = text("INITIALIZING", 13f, cyanSoft, true).apply { gravity = Gravity.CENTER }
        timeRow.addView(clockText)
        timeRow.addView(dateText)
        timePanel.addView(timeRow)
        root.addView(timePanel)

        val two = row()
        val weather = panelCard()
        weather.addView(sectionTitle("◉  LOCAL WEATHER"))
        weatherText = text("Set a city to load live weather", 14f, Color.WHITE)
        weather.addView(weatherText)
        weather.addView(actionButton("USE MY PHONE LOCATION") { usePhoneLocation() }, buttonParams())
        weather.addView(actionButton("ENTER CITY MANUALLY") { chooseWeatherCity() }, buttonParams())
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

        val features = panelCard()
        features.addView(sectionTitle("⬡  ULTRON SYSTEMS"))
        val featureRows = listOf(
            listOf("⏰ REMINDERS" to { showFeature("Reminders", "Reminder scheduling is being wired up. This section is included for your first full app test.") }, "▤ NOTES" to { showNotes() }, "↻ ROUTINES" to { showFeature("Routines", "Create routines such as Good morning, Study mode, and Night mode. Routine actions are being wired up.") }),
            listOf("◉ VOICE CLONE" to { configureAssemblyAIVoice() }, "⌕ WEB" to { showFeature("Web access", "ULTRON's web search interface is included. Search-provider integration is still pending.") }, "▣ PHONE CONTROL" to { showPhoneControls() }),
            listOf("◈ MEMORY" to { showMemories() }, "▦ CALENDAR" to { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)) }, "⚙ PERMISSIONS" to { openPermissionSettings() })
        )
        featureRows.forEach { items ->
            val featureRow = row()
            items.forEach { (label, action) -> featureRow.addView(actionButton(label, action), weightParams(1f)) }
            features.addView(featureRow)
        }
        root.addView(features)

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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.READ_CALENDAR)
        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 77)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 77) refreshAgenda()
        if (requestCode == 79) {
            if (results.any { it == PackageManager.PERMISSION_GRANTED }) startVoiceIfEnabled()
            else { UltronSettings.save(this, "voice_enabled", "false"); statusTextOrToast("Microphone permission is required for voice listening") }
        }
        if (requestCode == 78) {
            if (results.any { it == PackageManager.PERMISSION_GRANTED }) usePhoneLocation()
            else weatherText.text = "Location permission denied. Allow it in Android Settings > Apps > ULTRON > Permissions."
        }
    }

    private fun startVoiceIfEnabled() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            UltronSettings.get(this, "voice_enabled") != "true") return
        UltronSettings.save(this, "voice_status", "STARTING — WAITING FOR MICROPHONE")
        try { startForegroundService(Intent(this, VoiceWakeService::class.java)) }
        catch (e: Exception) {
            UltronSettings.save(this, "voice_status", "START FAILED — " + e.javaClass.simpleName)
            statusTextOrToast("Voice startup failed: " + e.javaClass.simpleName)
        }
    }

    private fun refreshVoiceStatus() {
        val enabled = UltronSettings.get(this, "voice_enabled") == "true"
        val state = if (enabled) UltronSettings.get(this, "voice_status") ?: "STARTING" else "VOICE OFF"
        statusText.text = "●  " + state
        statusText.setTextColor(when {
            !enabled -> Color.GRAY
            state.startsWith("LISTENING") || state.startsWith("HEARING") -> cyan
            state.startsWith("START") || state.startsWith("RECONNECT") || state.startsWith("PROCESS") -> Color.YELLOW
            else -> Color.RED
        })
    }

    private fun toggleVoice() {
        val enabled = UltronSettings.get(this, "voice_enabled") == "true"
        if (enabled) {
            UltronSettings.save(this, "voice_enabled", "false")
            UltronSettings.save(this, "voice_status", "VOICE OFF")
            startService(Intent(this, VoiceWakeService::class.java).setAction(VoiceWakeService.ACTION_STOP))
            statusTextOrToast("Voice listening paused")
        } else {
            UltronSettings.save(this, "voice_enabled", "true")
            UltronSettings.save(this, "voice_status", "STARTING — REQUESTING MICROPHONE")
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceIfEnabled()
            else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 79)
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


    private fun usePhoneLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 78)
            weatherText.text = "Allow location access, then tap USE MY PHONE LOCATION again."
            return
        }
        weatherText.text = "Finding your current location…"
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = manager.getProviders(true)
            val last = providers.mapNotNull { p -> try { manager.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time < 30 * 60 * 1000L) {
                loadWeatherAt(last.latitude, last.longitude, "Your current location")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = when {
                    providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> null
                }
                if (provider == null) { weatherText.text = "Location is turned off. Enable Location in Android settings."; return }
                manager.getCurrentLocation(provider, null, mainExecutor) { location ->
                    if (location != null) loadWeatherAt(location.latitude, location.longitude, "Your current location")
                    else runOnUiThread { weatherText.text = "Couldn't get a location fix. Turn on Location and try again." }
                }
            } else {
                weatherText.text = "No recent location. Turn on Location or enter a city manually."
            }
        } catch (_: SecurityException) {
            weatherText.text = "Location permission is missing. Allow it in Android app permissions."
        } catch (_: Exception) {
            weatherText.text = "Couldn't access your location. Check that Location is turned on."
        }
    }

    private fun loadWeatherAt(lat: Double, lon: Double, fallbackName: String) {
        weatherText.text = "Loading local weather…"
        Thread {
            try {
                val forecast = JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto").readText())
                val current = forecast.getJSONObject("current")
                val daily = forecast.getJSONObject("daily")
                var placeName = fallbackName
                try {
                    if (Geocoder.isPresent()) {
                        @Suppress("DEPRECATION")
                        val address = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                        placeName = address?.locality ?: address?.subAdminArea ?: address?.adminArea ?: fallbackName
                    }
                } catch (_: Exception) { }
                val label = "${current.getDouble("temperature_2m").toInt()}°  ${weatherDescription(current.getInt("weather_code"))}\n$placeName  •  H ${daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()}° / L ${daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()}°"
                runOnUiThread {
                    UltronSettings.save(this, "weather_lat", lat.toString())
                    UltronSettings.save(this, "weather_lon", lon.toString())
                    UltronSettings.save(this, "weather_city", placeName)
                    weatherText.text = label
                }
            } catch (_: Exception) {
                runOnUiThread { weatherText.text = "Weather unavailable. Check your internet connection and try again." }
            }
        }.start()
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
        val savedLat = UltronSettings.get(this, "weather_lat")?.toDoubleOrNull()
        val savedLon = UltronSettings.get(this, "weather_lon")?.toDoubleOrNull()
        if (savedLat != null && savedLon != null) {
            loadWeatherAt(savedLat, savedLon, UltronSettings.get(this, "weather_city") ?: "Saved location")
            return
        }
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

    private fun activeMediaController(): android.media.session.MediaController? {
        return try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val listener = android.content.ComponentName(this, WhatsAppNotificationListener::class.java)
            val sessions = manager.getActiveSessions(listener)
            sessions.firstOrNull {
                val state = it.playbackState?.state
                state == android.media.session.PlaybackState.STATE_PLAYING ||
                    state == android.media.session.PlaybackState.STATE_PAUSED ||
                    state == android.media.session.PlaybackState.STATE_BUFFERING ||
                    state == android.media.session.PlaybackState.STATE_CONNECTING
            } ?: sessions.firstOrNull()
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshMediaStatus() {
        if (!::musicText.isInitialized) return
        val controller = activeMediaController()
        musicText.text = when {
            controller != null -> {
                val title = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                val artist = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                val state = controller.playbackState?.state
                val status = if (state == android.media.session.PlaybackState.STATE_PLAYING) "PLAYING" else "PAUSED / READY"
                listOfNotNull(status, title, artist).joinToString("  •  ")
            }
            !isNotificationListenerEnabled() -> "Enable ULTRON Notification Access to control the active media session."
            else -> "No active media session. Start playback in Spotify, YouTube, or another media app."
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return enabled.contains(packageName)
    }

    private fun mediaKey(keyCode: Int) {
        val controller = activeMediaController()
        if (controller == null) {
            if (!isNotificationListenerEnabled()) {
                statusTextOrToast("Enable ULTRON Notification Access to control media.")
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
            } else {
                statusTextOrToast("No active media session. Start playing media first.")
            }
            refreshMediaStatus()
            return
        }
        try {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controller.transportControls.skipToPrevious()
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> controller.transportControls.skipToNext()
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                }
                else -> statusTextOrToast("Unsupported media action")
            }
            handler.postDelayed({ refreshMediaStatus() }, 250)
        } catch (_: SecurityException) {
            statusTextOrToast("Android denied media-session access. Re-enable ULTRON Notification Access.")
        } catch (_: Exception) {
            statusTextOrToast("The active media app rejected that control.")
        }
    }

    private fun sendToAI() {
        val question = chatInput.text.toString().trim()
        if (question.isBlank() || chatBusy) return
        val key = UltronSettings.get(this, "openai_api_key")
        if (key.isNullOrBlank()) { chatLog.text = "Add your Groq API key in SETTINGS first."; return }
        chatBusy = true
        chatInput.setText("")
        chatLog.text = "YOU: $question\n\nULTRON: Thinking…"
        val priorConversation = UltronConversation.transcript(this)
        UltronConversation.addTurn(this, "User", question)
        Thread {
            val answer = AIController.ask(key, question, UltronMemory.recall(this), priorConversation)
            UltronConversation.addTurn(this, "ULTRON", answer)
            runOnUiThread {
                chatLog.text = "YOU: $question\n\nULTRON: $answer"
                chatBusy = false
                UltronVoice.speak(this, answer)
            }
        }.start()
    }

    private fun showSettings() {
        val options = arrayOf("AI / Groq API key", "AssemblyAI voice setup", "WhatsApp notifications", "Calendar permission", "Weather location", "Saved memories", "App permissions", "Accessibility / phone control", "About / feature status")
        AlertDialog.Builder(this).setTitle("ULTRON SETTINGS").setItems(options) { _, which ->
            when (which) {
                0 -> credentialDialog("Groq API key", "openai_api_key")
                1 -> configureAssemblyAIVoice()
                2 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                3 -> requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 77)
                4 -> usePhoneLocation()
                5 -> showMemories()
                6 -> openPermissionSettings()
                7 -> openAccessibilitySettings()
                8 -> showFeature("ULTRON feature status", "Included now: AI chat, saved memory, conversation history, live clock, weather, calendar, notifications, media controls, app launcher, notes, reminders section, routines section, web section, phone controls section, and voice-sample import. Some advanced integrations still need implementation and device testing.")
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

    private fun showFeature(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("GOT IT", null).show()
    }

    private fun showNotes() {
        val field = EditText(this).apply {
            hint = "Write a note for ULTRON…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            minLines = 3
            setText(UltronSettings.get(this@MainActivity, "quick_note") ?: "")
        }
        AlertDialog.Builder(this).setTitle("ULTRON NOTES").setView(field)
            .setPositiveButton("SAVE") { _, _ ->
                UltronSettings.save(this, "quick_note", field.text.toString())
                statusTextOrToast("Note saved")
            }.setNeutralButton("VIEW") { _, _ ->
                showFeature("Saved note", UltronSettings.get(this, "quick_note")?.ifBlank { "No note saved." } ?: "No note saved.")
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun configureAssemblyAIVoice() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val instructions = text(
            "1. Open your AssemblyAI dashboard and create a voice clone from a recording you have permission to use.\n2. Copy the cloned voice ID.\n3. Paste your API key and voice ID below. ULTRON will use that exact voice for spoken replies.",
            13f, Color.WHITE
        )
        fields.addView(instructions, buttonParams())
        val apiKeyField = EditText(this).apply {
            hint = "AssemblyAI API key"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = 129
            setText(UltronSettings.get(this@MainActivity, "assemblyai_api_key") ?: "")
        }
        fields.addView(apiKeyField, buttonParams())
        val voiceIdField = EditText(this).apply {
            hint = "Cloned voice ID"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(UltronSettings.get(this@MainActivity, "assemblyai_voice_id") ?: "")
        }
        fields.addView(voiceIdField, buttonParams())
        AlertDialog.Builder(this).setTitle("ASSEMBLYAI CLONED VOICE")
            .setView(fields)
            .setPositiveButton("SAVE & ACTIVATE") { _, _ ->
                val key = apiKeyField.text.toString().trim()
                val id = voiceIdField.text.toString().trim()
                if (key.isBlank() || id.isBlank()) {
                    statusTextOrToast("Both the AssemblyAI API key and cloned voice ID are required.")
                } else {
                    UltronSettings.save(this, "assemblyai_api_key", key)
                    UltronSettings.save(this, "assemblyai_voice_id", id)
                    UltronVoice.speak(this, "ULTRON voice system activated. This reply is being generated with your AssemblyAI cloned voice.")
                    showFeature("CLONED VOICE ACTIVATED", "AssemblyAI is now selected for ULTRON speech. If the API key or voice ID is invalid, ULTRON will show an error instead of silently switching to the Android voice.")
                }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showPhoneControls() {
        val options = arrayOf("Open Android settings", "Open app settings", "Open dialer", "Open Bluetooth settings", "Open Wi-Fi settings")
        AlertDialog.Builder(this).setTitle("PHONE CONTROLS").setItems(options) { _, which ->
            val intent = when (which) {
                0 -> Intent(Settings.ACTION_SETTINGS)
                1 -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                2 -> Intent(Intent.ACTION_DIAL)
                3 -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                else -> Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            try { startActivity(intent) } catch (_: Exception) { statusTextOrToast("That control is unavailable") }
        }.show()
    }

    private fun openPermissionSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { showFeature("Permissions", "Open Android Settings > Apps > ULTRON > Permissions.") }
    }

    private fun openAccessibilitySettings() {
        showFeature(
            "ENABLE ULTRON PHONE CONTROL",
            "Find ULTRON Phone Control and switch it on. Review Android's accessibility warning and approve only if you want ULTRON to use navigation controls. You can turn it off any time in Accessibility settings."
        )
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (_: Exception) { statusTextOrToast("Open Android Settings > Accessibility > Installed apps > ULTRON Phone Control.") }
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
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
            refreshVoiceStatus()
            // Do not auto-start the microphone service on every Activity resume.
            // Voice starts only after the user explicitly enables it with the voice control.
        }
    }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private class CoreView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            contentDescription = "Animated blue holographic ULTRON AI core"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = max(1f, width.coerceAtMost(height) * .31f)
            val pulse = 0.94f + 0.06f * kotlin.math.sin(Math.toRadians(phase.toDouble())).toFloat()

            // Soft blue aura and bright glass-like center.
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx, cy, radius * 1.75f,
                intArrayOf(Color.argb(105, 0, 185, 255), Color.argb(35, 0, 105, 255), Color.TRANSPARENT),
                floatArrayOf(0f, .55f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius * 1.75f, paint)
            paint.shader = null

            paint.color = Color.argb(90, 0, 185, 255)
            paint.maskFilter = android.graphics.BlurMaskFilter(radius * .24f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(cx, cy, radius * .82f * pulse, paint)
            paint.maskFilter = null

            // Layered rotating orbital rings.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.argb(220, 75, 220, 255)
            paint.strokeWidth = 1.5f
            canvas.drawCircle(cx, cy, radius * pulse, paint)
            paint.color = Color.argb(150, 25, 150, 255)
            paint.strokeWidth = 2.2f
            canvas.save()
            canvas.rotate(phase, cx, cy)
            canvas.drawOval(cx - radius * 1.25f, cy - radius * .48f, cx + radius * 1.25f, cy + radius * .48f, paint)
            canvas.restore()
            canvas.save()
            canvas.rotate(-phase * .72f, cx, cy)
            paint.color = Color.argb(190, 65, 205, 255)
            paint.strokeWidth = 1.4f
            canvas.drawOval(cx - radius * .5f, cy - radius * 1.3f, cx + radius * .5f, cy + radius * 1.3f, paint)
            canvas.restore()

            // Bright inner plasma sphere.
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx - radius * .2f, cy - radius * .22f, radius * 1.05f,
                intArrayOf(Color.WHITE, Color.rgb(125, 235, 255), Color.rgb(0, 135, 255), Color.argb(210, 0, 35, 125)),
                floatArrayOf(0f, .16f, .62f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius * .72f * pulse, paint)
            paint.shader = null

            // Small orbiting energy points.
            paint.color = Color.WHITE
            for (i in 0..7) {
                val a = Math.toRadians((phase * 1.4f + i * 45).toDouble())
                val orbit = radius * (1.08f + if (i % 2 == 0) .12f else 0f)
                val x = cx + kotlin.math.cos(a).toFloat() * orbit
                val y = cy + kotlin.math.sin(a).toFloat() * orbit
                canvas.drawCircle(x, y, if (i % 2 == 0) 2.3f else 1.4f, paint)
            }

            phase = (phase + 2.0f) % 360f
            postInvalidateDelayed(40)
        }
    }
}
