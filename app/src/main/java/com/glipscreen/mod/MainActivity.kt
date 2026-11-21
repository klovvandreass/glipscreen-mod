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
        fun makeText(value: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            }
        fun gap(parent: LinearLayout, height: Int) {
            parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
        }
        fun panel(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = surface(0xFF121F33.toInt(), 18, 0xFF20334F.toInt())
        }
        fun sectionTitle(value: String): TextView =
            makeText(value, 12f, 0xFF91A9C7.toInt(), true).apply {
                letterSpacing = 0.08f
            }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFF0A1220.toInt())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        val prefs = getSharedPreferences("glip_settings", MODE_PRIVATE)

        // Brand header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = makeText("K", 23f, 0xFF75B9FF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = surface(0xFF17365C.toInt(), 13)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        header.addView(mark)
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brand.addView(makeText("Klovv Screen", 21f, 0xFFFFFFFF.toInt(), true))
        brand.addView(makeText("SCREEN RECORDER", 10f, 0xFF8299B7.toInt(), true))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(makeText("●  READY", 10f, 0xFF69D9A4.toInt(), true))
        root.addView(header)
        gap(root, 28)

        root.addView(makeText("Record your gameplay", 25f, 0xFFFFFFFF.toInt(), true))
        gap(root, 5)
        root.addView(makeText("Simple controls. Clear recordings.", 14f, 0xFF91A4BE.toInt()))
        gap(root, 20)

        // Main recording card
        val recordCard = panel()
        recordCard.addView(sectionTitle("RECORDING STATUS"))
        gap(recordCard, 10)
        status = makeText("Ready to record", 16f, 0xFF6DDEAA.toInt(), true)
        recordCard.addView(status)
        gap(recordCard, 15)

        startButton = Button(this).apply {
            text = "●   START RECORDING"
            isAllCaps = false
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            background = surface(0xFF2678D8.toInt(), 14)
            minHeight = dp(54)
            setOnClickListener { requestRecording() }
        }
        recordCard.addView(startButton, LinearLayout.LayoutParams(-1, dp(54)))
        gap(recordCard, 9)
        val stop = Button(this).apply {
            text = "STOP  /  CANCEL"
            isAllCaps = false
            textSize = 13f
            setTextColor(0xFFB8C9DF.toInt())
            background = surface(0xFF1A2A40.toInt(), 12, 0xFF304660.toInt())
            minHeight = dp(46)
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
        recordCard.addView(stop, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(recordCard)
        gap(root, 18)

        // Settings card
        val settingsCard = panel()
        settingsCard.addView(sectionTitle("RECORDING SETUP"))
        gap(settingsCard, 12)

        fun addSetting(label: String, control: View) {
            settingsCard.addView(makeText(label, 13f, 0xFFE3ECF8.toInt(), true))
            gap(settingsCard, 5)
            settingsCard.addView(control, LinearLayout.LayoutParams(-1, dp(48)))
            gap(settingsCard, 13)
        }

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

        addSetting("VIDEO QUALITY", resolution)
        addSetting("FRAME RATE", frameRate)
        addSetting("VIDEO CODEC", codec)
        addSetting("COUNTDOWN", countdown)
        addSetting("VIDEO BITRATE PRESET", quality)
        root.addView(settingsCard)
        gap(root, 18)

        // Audio card
        val audioCard = panel()
        audioCard.addView(sectionTitle("AUDIO SOURCE"))
        gap(audioCard, 8)
        val choices = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        choices.addView(RadioButton(this).apply {
            id = 100
            text = "Internal audio"
            textSize = 14f
            setTextColor(0xFFE3ECF8.toInt())
            isChecked = Build.VERSION.SDK_INT >= 29
            isEnabled = Build.VERSION.SDK_INT >= 29
        })
        choices.addView(RadioButton(this).apply {
            id = 101
            text = "Microphone"
            textSize = 14f
            setTextColor(0xFFE3ECF8.toInt())
            isChecked = Build.VERSION.SDK_INT < 29
        })
        choices.addView(RadioButton(this).apply {
            id = 102
            text = "Internal + microphone"
            textSize = 14f
            setTextColor(0xFFE3ECF8.toInt())
            isEnabled = Build.VERSION.SDK_INT >= 29
        })
        choices.setOnCheckedChangeListener { _, checked ->
            selectedMode = when (checked) {
                101 -> RecordingService.MODE_MIC
                102 -> RecordingService.MODE_BOTH
                else -> RecordingService.MODE_INTERNAL
            }
        }
        selectedMode = prefs.getInt(
            "audio_mode",
            if (Build.VERSION.SDK_INT >= 29) RecordingService.MODE_INTERNAL else RecordingService.MODE_MIC
        )
        choices.check(when (selectedMode) {
            RecordingService.MODE_MIC -> 101
            RecordingService.MODE_BOTH -> 102
            else -> 100
        })
        audioCard.addView(choices)
        root.addView(audioCard)
        gap(root, 18)

        val gallery = Button(this).apply {
            text = "▣   MY RECORDINGS"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFDCE9F8.toInt())
            background = surface(0xFF172941.toInt(), 14, 0xFF2C4563.toInt())
            minHeight = dp(52)
            setOnClickListener { showRecordings() }
        }
        root.addView(gallery, LinearLayout.LayoutParams(-1, dp(52)))
        gap(root, 13)
        root.addView(makeText(
            "Klovv Screen adapts recording settings to the capabilities of your Android device.",
            12f, 0xFF7188A6.toInt()
        ))
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
                setColor(0xFF1A2A40.toInt())
                cornerRadius = 12 * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), 0xFF304660.toInt())
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
