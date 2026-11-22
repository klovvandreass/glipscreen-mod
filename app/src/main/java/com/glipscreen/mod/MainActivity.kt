package com.glipscreen.mod

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 4101
    private val audioPermissionRequest = 4102
    private val notificationPermissionRequest = 4103
    private lateinit var status: TextView
    private lateinit var resolution: Spinner
    private lateinit var frameRate: Spinner
    private lateinit var codec: Spinner
    private lateinit var countdown: Spinner
    private lateinit var quality: Spinner
    private lateinit var startButton: Button
    private var selectedMode = if (Build.VERSION.SDK_INT >= 29) RecordingService.MODE_INTERNAL else RecordingService.MODE_MIC
    private var captureFlowActive = false
    private var countdownTimer: android.os.CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
        fun surface(color: Int, radius: Int, stroke: Int? = null) =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke != null) setStroke(dp(1), stroke)
            }
        fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                includeFontPadding = false
                if (bold) {
                    setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                    letterSpacing = 0.005f
                }
            }
        fun gap(parent: LinearLayout, height: Int) {
            parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
        }
        fun panel(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            background = surface(0xFF0B1727.toInt(), 18, 0xFF1B3556.toInt())
        }
        fun section(value: String) = label(value, 12f, 0xFF8B9AB2.toInt(), true).apply {
            letterSpacing = 0.08f
        }
        fun pageScroll(): Pair<android.widget.ScrollView, LinearLayout> {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(28))
            }
            val scroll = android.widget.ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(0xFF030508.toInt())
                addView(content)
            }
            return scroll to content
        }

        val prefs = getSharedPreferences("glip_settings", MODE_PRIVATE)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF030508.toInt())
        }
        val pages = android.widget.FrameLayout(this)
        shell.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(8), dp(7), dp(8))
            background = surface(0xFF070D17.toInt(), 0, 0xFF14243A.toInt())
        }
        shell.addView(nav, LinearLayout.LayoutParams(-1, dp(66)))
        setContentView(shell)

        // Hidden selection controls keep the existing recording engine inputs intact.
        resolution = spinner(listOf("Auto (recommended)", "480p", "720p HD", "1080p Full HD", "1440p 2K", "2160p 4K"))
        frameRate = spinner(listOf("Auto (recommended)", "24 FPS", "30 FPS", "60 FPS", "90 FPS", "120 FPS"))
        codec = spinner(listOf("H.264 (most compatible)", "H.265 / HEVC (smaller file, if supported)"))
        countdown = spinner(listOf("No countdown", "3 seconds", "5 seconds", "10 seconds"))
        quality = spinner(listOf("Standard (smaller file)", "High quality", "Maximum quality"))
        resolution.setSelection(prefs.getInt("resolution", 0).coerceIn(0, 5))
        frameRate.setSelection(prefs.getInt("fps", 0).coerceIn(0, 5))
        codec.setSelection(prefs.getInt("codec", 0).coerceIn(0, 1))
        countdown.setSelection(prefs.getInt("countdown", 1).coerceIn(0, 3))
        quality.setSelection(prefs.getInt("quality", 1).coerceIn(0, 2))
        selectedMode = prefs.getInt(
            "audio_mode",
            if (Build.VERSION.SDK_INT >= 29) RecordingService.MODE_INTERNAL else RecordingService.MODE_MIC
        )

        // HOME — premium dashboard, with recording controls and tappable presets.
        var navigateTo: (Int) -> Unit = {}
        val (homeScroll, home) = pageScroll()
        pages.addView(homeScroll)
        homeScroll.setPadding(0, dp(8), 0, 0)
        homeScroll.clipToPadding = false

        home.addView(label("KLOVV SCREEN", 25f, 0xFFFFFFFF.toInt(), true))
        gap(home, 6)
        home.addView(label("SCREEN RECORDER  •  SIMPLE. CLEAN. READY.", 10f, 0xFF789CCB.toInt(), true))
        gap(home, 7)
        home.addView(label("Capture your screen with clarity and control.", 13f, 0xFF9AABC1.toInt()))
        gap(home, 22)

        val hero = panel().apply {
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = surface(0xFF0A1426.toInt(), 24, 0xFF1D3D66.toInt())
        }
        hero.addView(section("READY WHEN YOU ARE"))
        gap(hero, 14)
        val visual = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(154))
        }
        val halo = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF203F65.toInt())
                setStroke(dp(1), 0xFF315B8E.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER)
        }
        visual.addView(halo)
        val inner = TextView(this).apply {
            text = "▣"
            textSize = 47f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = surface(0xFF1D5FB8.toInt(), 28)
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER)
        }
        visual.addView(inner)
        hero.addView(visual)
        gap(hero, 8)
        hero.addView(label("Capture every moment", 21f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER })
        gap(hero, 5)
        hero.addView(label("Record gameplay, tutorials, and anything on your screen.", 13f, 0xFFB8C2D2.toInt()).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        gap(hero, 17)
        status = label("●  Ready to record", 13f, 0xFF70DDB0.toInt(), true).apply { gravity = Gravity.CENTER }
        hero.addView(status)
        gap(hero, 12)
        startButton = Button(this).apply {
            text = "START RECORDING"
            isAllCaps = false
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            background = surface(0xFF1D5FB8.toInt(), 16)
            minHeight = dp(54)
            setOnClickListener { requestRecording() }
        }
        hero.addView(startButton, LinearLayout.LayoutParams(-1, dp(54)))
        gap(hero, 8)
        val stopButton = Button(this).apply {
            text = "STOP  /  CANCEL"
            isAllCaps = false
            textSize = 12f
            setTextColor(0xFFB9C9DD.toInt())
            background = surface(0xFF111C2D.toInt(), 12, 0xFF263B5B.toInt())
            minHeight = dp(42)
            setOnClickListener {
                val timer = countdownTimer
                if (timer != null) {
                    timer.cancel()
                    countdownTimer = null
                    captureFlowActive = false
                    startButton.isEnabled = true
                    status.text = "Countdown cancelled"
                } else {
                    val i = Intent(this@MainActivity, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_STOP
                    }
                    startService(i)
                    status.text = "Stopping recording…"
                }
            }
        }
        hero.addView(stopButton, LinearLayout.LayoutParams(-1, dp(42)))
        home.addView(hero)
        gap(home, 20)

        home.addView(label("Game Launcher", 18f, 0xFFFFFFFF.toInt(), true))
        gap(home, 4)
        home.addView(label("Your favorite games, all in one place.", 13f, 0xFF8795AA.toInt()))
        gap(home, 12)
        val gameLauncherCard = panel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        val gameIcon = TextView(this).apply {
            text = "🎮"
            textSize = 27f
            gravity = Gravity.CENTER
            background = surface(0xFF132B4B.toInt(), 15)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        }
        gameLauncherCard.addView(gameIcon)
        val gameLauncherText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
            addView(label("Game shortcuts", 15f, 0xFFFFFFFF.toInt(), true))
            gap(this, 4)
            addView(label("Your game icons will appear here.", 12f, 0xFF9AA8BC.toInt()))
        }
        gameLauncherCard.addView(gameLauncherText, LinearLayout.LayoutParams(0, -2, 1f))
        gameLauncherCard.addView(label("›", 27f, 0xFF6D9EDB.toInt(), true))
        home.addView(gameLauncherCard)
        gap(home, 20)

        home.addView(label("Quick settings", 18f, 0xFFFFFFFF.toInt(), true))
        gap(home, 4)
        home.addView(label("Tap any option to customize your recording.", 13f, 0xFF8795AA.toInt()))
        gap(home, 12)

        fun settingTile(title: String, value: String, icon: String, onTap: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = surface(0xFF0B1422.toInt(), 16, 0xFF263D59.toInt())
                isClickable = true
                isFocusable = true
                addView(label(icon, 18f, 0xFF6D9EDB.toInt(), true))
                gap(this, 9)
                addView(label(title, 12f, 0xFF8B9AB2.toInt(), true))
                addView(label(value, 15f, 0xFFFFFFFF.toInt(), true).apply { tag = "setting_value" })
                setOnClickListener { onTap() }
            }
        }
        fun chooseSetting(title: String, items: List<String>, spinnerRef: Spinner, prefKey: String, tile: LinearLayout) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items.toTypedArray(), spinnerRef.selectedItemPosition) { dialog, which ->
                    spinnerRef.setSelection(which)
                    prefs.edit().putInt(prefKey, which).apply()
                    tile.findViewWithTag<TextView>("setting_value")?.text = items[which]
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
        val qualityItems = listOf("Standard", "High quality", "Maximum quality")
        val fpsItems = listOf("Auto", "24 FPS", "30 FPS", "60 FPS", "90 FPS", "120 FPS")
        val codecItems = listOf("H.264", "H.265 / HEVC")
        val resolutionItems = listOf("Auto", "480p", "720p HD", "1080p Full HD", "1440p 2K", "2160p 4K")
        val grid1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var qualityTile: LinearLayout
        lateinit var fpsTile: LinearLayout
        lateinit var codecTile: LinearLayout
        lateinit var resolutionTile: LinearLayout
        qualityTile = settingTile("QUALITY", qualityItems[quality.selectedItemPosition], "◈") {
            chooseSetting("Recording quality", qualityItems, quality, "quality", qualityTile)
        }
        fpsTile = settingTile("FRAME RATE", fpsItems[frameRate.selectedItemPosition], "◷") {
            chooseSetting("Frame rate", fpsItems, frameRate, "fps", fpsTile)
        }
        grid1.addView(qualityTile, LinearLayout.LayoutParams(0, dp(104), 1f).apply { marginEnd = dp(7) })
        grid1.addView(fpsTile, LinearLayout.LayoutParams(0, dp(104), 1f).apply { marginStart = dp(7) })
        home.addView(grid1)
        gap(home, 10)
        val grid2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        codecTile = settingTile("VIDEO CODEC", codecItems[codec.selectedItemPosition], "▤") {
            chooseSetting("Video codec", codecItems, codec, "codec", codecTile)
        }
        resolutionTile = settingTile("RESOLUTION", resolutionItems[resolution.selectedItemPosition], "▣") {
            chooseSetting("Resolution", resolutionItems, resolution, "resolution", resolutionTile)
        }
        grid2.addView(codecTile, LinearLayout.LayoutParams(0, dp(104), 1f).apply { marginEnd = dp(7) })
        grid2.addView(resolutionTile, LinearLayout.LayoutParams(0, dp(104), 1f).apply { marginStart = dp(7) })
        home.addView(grid2)
        gap(home, 18)

        val quickCard = panel()
        quickCard.orientation = LinearLayout.HORIZONTAL
        quickCard.gravity = Gravity.CENTER_VERTICAL
        quickCard.addView(label("▣", 23f, 0xFF6D9EDB.toInt(), true))
        val quickText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("My recordings", 15f, 0xFFFFFFFF.toInt(), true))
            addView(label("Browse and manage saved videos", 12f, 0xFF8795AA.toInt()))
        }
        quickCard.addView(quickText, LinearLayout.LayoutParams(0, -2, 1f))
        quickCard.addView(Button(this).apply {
            text = "OPEN"
            isAllCaps = false
            textSize = 11f
            setTextColor(0xFF8AADE0.toInt())
            background = surface(0xFF142744.toInt(), 10)
            setOnClickListener { navigateTo(1) }
        })
        home.addView(quickCard)
        gap(home, 14)
        home.addView(label("Designed for smooth, simple screen recording.", 11f, 0xFF6D7D94.toInt()))

        // SETTINGS
        val (settingsScroll, settings) = pageScroll()
        pages.addView(settingsScroll)
        settings.addView(label("Settings", 27f, 0xFFFFFFFF.toInt(), true))
        gap(settings, 5)
        settings.addView(label("Keep your recording setup organized.", 14f, 0xFF8795AA.toInt()))
        gap(settings, 22)
        val recordingCard = panel()
        recordingCard.addView(section("VIDEO"))
        gap(recordingCard, 8)
        recordingCard.addView(label("Recording quality and performance", 16f, 0xFFFFFFFF.toInt(), true))
        gap(recordingCard, 4)
        recordingCard.addView(label("Choose the capture settings you want to use.", 12f, 0xFF9AA8BC.toInt()))
        gap(recordingCard, 12)

        fun preferenceRow(title: String, items: List<String>, spinnerRef: Spinner, prefKey: String): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(13), dp(12), dp(13))
                background = surface(0xFF101B2B.toInt(), 12)
                isClickable = true
                isFocusable = true
            }
            val titleView = label(title, 13f, 0xFFDCE7F5.toInt(), true)
            row.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            val valueView = label(items[spinnerRef.selectedItemPosition], 12f, 0xFF83A9D8.toInt(), true).apply {
                tag = "setting_value"
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            row.addView(valueView, LinearLayout.LayoutParams(-2, -2))
            row.setOnClickListener { chooseSetting(title, items, spinnerRef, prefKey, row) }
            return row
        }
        fun addPreferenceDivider(parent: LinearLayout) {
            parent.addView(View(this).apply {
                setBackgroundColor(0xFF1B2A40.toInt())
            }, LinearLayout.LayoutParams(-1, dp(7)))
        }
        recordingCard.addView(preferenceRow("Resolution", resolutionItems, resolution, "resolution"))
        addPreferenceDivider(recordingCard)
        recordingCard.addView(preferenceRow("Frame rate", fpsItems, frameRate, "fps"))
        addPreferenceDivider(recordingCard)
        recordingCard.addView(preferenceRow("Video codec", codecItems, codec, "codec"))
        addPreferenceDivider(recordingCard)
        recordingCard.addView(preferenceRow("Video quality", qualityItems, quality, "quality"))
        addPreferenceDivider(recordingCard)
        recordingCard.addView(preferenceRow("Start countdown", listOf("No countdown", "3 seconds", "5 seconds", "10 seconds"), countdown, "countdown"))
        settings.addView(recordingCard)
        gap(settings, 14)
        val audioCard = panel()
        audioCard.addView(section("AUDIO"))
        gap(audioCard, 10)
        audioCard.addView(label("Choose what should be captured.", 13f, 0xFF9AA8BC.toInt()))
        gap(audioCard, 8)
        val audioChoices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            "Internal audio" to RecordingService.MODE_INTERNAL,
            "Microphone" to RecordingService.MODE_MIC,
            "Internal + microphone" to RecordingService.MODE_BOTH
        ).forEachIndexed { index, pair ->
            audioChoices.addView(RadioButton(this).apply {
                id = 200 + index
                text = pair.first
                textSize = 14f
                setTextColor(0xFFDCE7F5.toInt())
                isEnabled = index == 1 || Build.VERSION.SDK_INT >= 29
            })
        }
        audioChoices.setOnCheckedChangeListener { _, checked ->
            selectedMode = when (checked) {
                201 -> RecordingService.MODE_MIC
                202 -> RecordingService.MODE_BOTH
                else -> RecordingService.MODE_INTERNAL
            }
        }
        audioChoices.check(when (selectedMode) {
            RecordingService.MODE_MIC -> 201
            RecordingService.MODE_BOTH -> 202
            else -> 200
        })
        audioCard.addView(audioChoices)
        settings.addView(audioCard)
        gap(settings, 14)
        val generalCard = panel()
        generalCard.addView(section("GENERAL"))
        gap(generalCard, 10)
        generalCard.addView(label("Klovv Screen", 16f, 0xFFFFFFFF.toInt(), true))
        gap(generalCard, 5)
        generalCard.addView(label("A clean and straightforward screen recorder.", 13f, 0xFF9AA8BC.toInt()))
        settings.addView(generalCard)

        // RECORDINGS
        val (videosScroll, videos) = pageScroll()
        pages.addView(videosScroll)
        videos.addView(label("My recordings", 27f, 0xFFFFFFFF.toInt(), true))
        gap(videos, 5)
        videos.addView(label("Your recordings, organized in one place.", 14f, 0xFF8795AA.toInt()))
        gap(videos, 22)
        val videosCard = panel()
        videosCard.addView(section("VIDEO LIBRARY"))
        gap(videosCard, 10)
        val count = loadRecordings().size
        videosCard.addView(label(if (count == 1) "1 saved recording" else "$count saved recordings", 19f, 0xFFFFFFFF.toInt(), true))
        gap(videosCard, 5)
        videosCard.addView(label("Saved in Movies/Klovv Screen", 13f, 0xFF9AA8BC.toInt()))
        gap(videosCard, 15)
        val browse = Button(this).apply {
            text = "BROWSE RECORDINGS"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = surface(0xFF1D5FB8.toInt(), 12)
            setOnClickListener { showRecordings() }
        }
        videosCard.addView(browse, LinearLayout.LayoutParams(-1, dp(50)))
        videos.addView(videosCard)

        // ABOUT
        val (aboutScroll, about) = pageScroll()
        pages.addView(aboutScroll)
        about.addView(label("About", 27f, 0xFFFFFFFF.toInt(), true))
        gap(about, 5)
        about.addView(label("Built to make screen recording feel effortless.", 14f, 0xFF8795AA.toInt()))
        gap(about, 22)
        val aboutCard = panel()
        aboutCard.gravity = Gravity.CENTER_HORIZONTAL
        aboutCard.addView(label("K", 34f, 0xFF6D9EDB.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = surface(0xFF132B4B.toInt(), 18)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
        })
        gap(aboutCard, 14)
        aboutCard.addView(label("Klovv Screen", 21f, 0xFFFFFFFF.toInt(), true))
        gap(aboutCard, 5)
        aboutCard.addView(label("SCREEN RECORDER", 11f, 0xFF8191AA.toInt(), true))
        gap(aboutCard, 16)
        aboutCard.addView(label("A focused screen recorder with a clean interface and practical controls.", 13f, 0xFF9AA8BC.toInt()).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        about.addView(aboutCard)

        val pageViews = listOf(homeScroll, videosScroll, settingsScroll, aboutScroll)
        val navButtons = ArrayList<Button>()
        val navLabels = listOf("⌂\nHome", "▣\nVideos", "⚙\nSettings", "ⓘ\nAbout")
        fun selectPage(index: Int) {
            pageViews.forEachIndexed { i, view ->
                view.visibility = if (i == index) View.VISIBLE else View.GONE
            }
            navButtons.forEachIndexed { i, button ->
                val selected = i == index
                button.setTextColor(if (selected) 0xFF6D9EDB.toInt() else 0xFF78869C.toInt())
                button.background = if (selected) surface(0xFF142B4A.toInt(), 12) else surface(0x00101B2B, 12)
            }
        }
        navLabels.forEachIndexed { index, title ->
            val button = Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 11f
                minHeight = dp(50)
                setPadding(dp(3), 0, dp(3), 0)
                setOnClickListener { selectPage(index) }
            }
            navButtons.add(button)
            nav.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        navigateTo = { index -> selectPage(index) }
        selectPage(0)
    }

    override fun onResume() {
        super.onResume()
        if (::startButton.isInitialized) {
            android.os.Handler(mainLooper).postDelayed({
                if (!isFinishing && ::startButton.isInitialized) {
                    startButton.isEnabled = !RecordingService.isRecordingActive &&
                        countdownTimer == null && !captureFlowActive
                }
            }, 600L)
        }
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        countdownTimer = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (::resolution.isInitialized && ::frameRate.isInitialized && ::codec.isInitialized && ::countdown.isInitialized && ::quality.isInitialized) {
            getSharedPreferences("glip_settings", MODE_PRIVATE).edit()
                .putInt("resolution", resolution.selectedItemPosition)
                .putInt("fps", frameRate.selectedItemPosition)
                .putInt("codec", codec.selectedItemPosition)
                .putInt("countdown", countdown.selectedItemPosition)
                .putInt("quality", quality.selectedItemPosition)
                .putInt("audio_mode", selectedMode).apply()
        }
    }

    private fun spinner(items: List<String>): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setPadding(12, 4, 12, 4)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF101A2B.toInt())
                cornerRadius = 12 * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), 0xFF263B5B.toInt())
            }
        }
    }

    private data class RecordingItem(val uri: Uri, val title: String, val details: String)

    private fun showRecordings() {
        val items = loadRecordings()
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle("My recordings")
                .setMessage("Belum ada video Klovv Screen. Hasil rekaman tersimpan di Movies/Klovv Screen.")
                .setPositiveButton("OK", null).show()
            return
        }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1,
            items.map { it.title + "\n" + it.details })
        val dialog = AlertDialog.Builder(this).setTitle("MY RECORDINGS • ${items.size}")
            .setView(list).setNegativeButton("CLOSE", null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            AlertDialog.Builder(this).setTitle(item.title)
                .setItems(arrayOf("Play video", "Share video", "Video details", "Rename video", "Delete video")) { _, choice ->
                    when (choice) {
                        0 -> {
                            val play = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try { startActivity(play) } catch (_: Exception) {
                                status.text = "No video player found"
                            }
                        }
                        1 -> {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(share, "Share recording"))
                        }
                        2 -> {
                            val retriever = android.media.MediaMetadataRetriever()
                            val info = try {
                                retriever.setDataSource(this, item.uri)
                                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
                                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                                val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.div(1000)
                                val extractor = android.media.MediaExtractor()
                                val fps = try {
                                    extractor.setDataSource(this, item.uri, null)
                                    var detected: String? = null
                                    for (track in 0 until extractor.trackCount) {
                                        val format = extractor.getTrackFormat(track)
                                        if (format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                                            format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                                            detected = format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toString()
                                            break
                                        }
                                    }
                                    detected
                                } catch (e: Exception) {
                                    android.util.Log.w("KLOVVRecorder", "Could not inspect recording frame rate", e)
                                    null
                                } finally {
                                    try { extractor.release() } catch (_: Exception) { }
                                }
                                "Resolution: ${w} × ${h}\nFrame rate: ${fps?.let { "$it FPS" } ?: "Not available"}\nBitrate: ${bitrate?.let { "$it kbps" } ?: "Not available"}\n${item.details}"
                            } catch (_: Exception) { item.details } finally { try { retriever.release() } catch (_: Exception) { } }
                            AlertDialog.Builder(this).setTitle("VIDEO DETAILS").setMessage(info).setPositiveButton("OK", null).show()
                        }
                        3 -> {
                            val input = EditText(this).apply {
                                setText(item.title.substringBeforeLast("."))
                                hint = "Video name"
                            }
                            AlertDialog.Builder(this).setTitle("Rename recording").setView(input)
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("SAVE") { _, _ ->
                                    val newName = input.text.toString().trim().replace(Regex("[^A-Za-z0-9 _-]"), "")
                                    if (newName.isNotBlank()) {
                                        try {
                                            val values = android.content.ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, "$newName.mp4") }
                                            val updated = contentResolver.update(item.uri, values, null, null)
                                            if (updated > 0) {
                                                status.text = "Recording renamed"
                                                dialog.dismiss()
                                                showRecordings()
                                            } else {
                                                status.text = "Recording was not renamed"
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("KLOVVRecorder", "Rename failed", e)
                                            status.text = "Could not rename recording"
                                        }
                                    }
                                }.show()
                        }
                        4 -> AlertDialog.Builder(this).setMessage("Delete this recording?")
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("DELETE") { _, _ ->
                                try {
                                    val deleted = contentResolver.delete(item.uri, null, null)
                                    if (deleted > 0) {
                                        status.text = "Recording deleted"
                                        dialog.dismiss()
                                        showRecordings()
                                    } else {
                                        status.text = "Recording was not deleted"
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("KLOVVRecorder", "Delete failed", e)
                                    status.text = "Could not delete recording"
                                }
                            }.show()
                    }
                }.show()
        }
        dialog.show()
    }

    private fun loadRecordings(): List<RecordingItem> {
        val result = ArrayList<RecordingItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val selection: String
        val args: Array<String>?
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "(${MediaStore.Video.Media.RELATIVE_PATH} = ? OR ${MediaStore.Video.Media.RELATIVE_PATH} = ?)"
            args = arrayOf("Movies/Klovv Screen/", "Movies/KLOVV MOD/")
        } else {
            selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            args = arrayOf("KLOVV_%")
        }
        try {
            contentResolver.query(collection, projection, selection, args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    val name = cursor.getString(nameCol) ?: "Klovv recording"
                    val seconds = cursor.getLong(durationCol) / 1000
                    val sizeMb = cursor.getLong(sizeCol) / (1024.0 * 1024.0)
                    val date = java.text.SimpleDateFormat("dd MMM yyyy • HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(cursor.getLong(dateCol) * 1000))
                    result.add(RecordingItem(uri, name, "${date} • ${seconds}s • %.1f MB".format(sizeMb)))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("KLOVVRecorder", "Gallery query failed", e)
        }
        return result
    }

    private fun requestRecording() {
        if (captureFlowActive || countdownTimer != null) return
        captureFlowActive = true
        startButton.isEnabled = false
        continueRecordingPermissionFlow()
    }

    private fun continueRecordingPermissionFlow() {
        // Floating controls are optional: recording must still work when overlay access is denied.
        if (Build.VERSION.SDK_INT >= 26 && !Settings.canDrawOverlays(this)) {
            status.text = "Floating controls unavailable; notification controls will remain available"
        }
        if ((selectedMode == RecordingService.MODE_MIC || selectedMode == RecordingService.MODE_BOTH) &&
            Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequest)
            return
        }
        launchCapturePermission()
    }

    private fun launchCapturePermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
        } catch (e: Exception) {
            captureFlowActive = false
            startButton.isEnabled = true
            status.text = "Could not open screen-capture permission"
            android.util.Log.e("KLOVVRecorder", "Capture permission launch failed", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) continueRecordingPermissionFlow()
            else {
                captureFlowActive = false
                startButton.isEnabled = true
                status.text = "Audio permission not granted"
            }
        } else if (requestCode == notificationPermissionRequest) {
            // Recording can continue if notifications are denied; Android may still show the FGS task entry.
            launchCapturePermission()
        }
    }

    @Deprecated("Uses compatible Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            captureFlowActive = false
            if (resultCode == RESULT_OK && data != null) {
                val maxDimension = when (resolution.selectedItemPosition) { 1 -> 854; 2 -> 1280; 3 -> 1920; 4 -> 2560; 5 -> 3840; else -> 0 }
                val requestedFps = when (frameRate.selectedItemPosition) { 1 -> 24; 2 -> 30; 3 -> 60; 4 -> 90; 5 -> 120; else -> 0 }
                val serviceIntent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                    putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(RecordingService.EXTRA_RESULT_DATA, data)
                    putExtra(RecordingService.EXTRA_AUDIO_MODE, selectedMode)
                    putExtra(RecordingService.EXTRA_MAX_DIMENSION, maxDimension)
                    putExtra(RecordingService.EXTRA_REQUESTED_FPS, requestedFps)
                    putExtra(RecordingService.EXTRA_PREFER_HEVC, codec.selectedItemPosition == 1)
                    putExtra(RecordingService.EXTRA_QUALITY, quality.selectedItemPosition)
                }
                val delaySeconds = when (countdown.selectedItemPosition) { 1 -> 3; 2 -> 5; 3 -> 10; else -> 0 }
                if (delaySeconds == 0) {
                    startButton.isEnabled = false
                    try {
                        RecordingService.setRecordingPending(true)
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                        status.text = "Recording starting…"
                    } catch (e: Exception) {
                        RecordingService.setRecordingPending(false)
                        startButton.isEnabled = true
                        status.text = "Could not start recording service"
                        android.util.Log.e("KLOVVRecorder", "Service launch failed", e)
                    }
                } else {
                    startButton.isEnabled = false
                    countdownTimer = object : android.os.CountDownTimer(delaySeconds * 1000L, 1000L) {
                        override fun onTick(millisUntilFinished: Long) {
                            status.text = "Recording starts in " + ((millisUntilFinished + 999) / 1000) + " seconds • tap STOP / CANCEL to cancel"
                        }
                        override fun onFinish() {
                            countdownTimer = null
                            if (!isFinishing) {
                                try {
                                    RecordingService.setRecordingPending(true)
                                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                                    status.text = "Recording starting…"
                                } catch (e: Exception) {
                                    RecordingService.setRecordingPending(false)
                                    startButton.isEnabled = true
                                    status.text = "Could not start recording service"
                                    android.util.Log.e("KLOVVRecorder", "Service launch failed", e)
                                }
                            }
                        }
                    }.start()
                }
            } else {
                startButton.isEnabled = true
                status.text = "Screen-capture permission cancelled"
            }
        }
    }
}
