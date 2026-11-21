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
    private var selectedMode = if (Build.VERSION.SDK_INT >= 29) RecordingService.MODE_INTERNAL else RecordingService.MODE_MIC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFF10131B.toInt())
        }
        fun text(value: String, size: Float, color: Int = 0xFFFFFFFF.toInt()) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(4, 10, 4, 8)
        }
        root.addView(text("Klovv Screen", 27f))
        root.addView(text("PRO SCREEN RECORDER  •  ANDROID", 12f, 0xFF9BA7BC.toInt()))
        status = text("Ready to record", 14f, 0xFF70E0AE.toInt())
        root.addView(status)
        root.addView(text("VIDEO QUALITY", 12f, 0xFF9BA7BC.toInt()))
        resolution = spinner(listOf("Auto (recommended)", "480p", "720p HD", "1080p Full HD", "1440p 2K", "2160p 4K"))
        root.addView(resolution)
        root.addView(text("FRAME RATE", 12f, 0xFF9BA7BC.toInt()))
        frameRate = spinner(listOf("Auto (recommended)", "24 FPS", "30 FPS", "60 FPS", "90 FPS", "120 FPS"))
        root.addView(frameRate)
        root.addView(text("VIDEO CODEC", 12f, 0xFF9BA7BC.toInt()))
        codec = spinner(listOf("H.264 (most compatible)", "H.265 / HEVC (smaller file, if supported)"))
        root.addView(codec)
        val prefs = getSharedPreferences("glip_settings", MODE_PRIVATE)
        resolution.setSelection(prefs.getInt("resolution", 0).coerceIn(0, 5))
        frameRate.setSelection(prefs.getInt("fps", 0).coerceIn(0, 5))
        codec.setSelection(prefs.getInt("codec", 0).coerceIn(0, 1))
        root.addView(text("COUNTDOWN", 12f, 0xFF9BA7BC.toInt()))
        countdown = spinner(listOf("No countdown", "3 seconds", "5 seconds", "10 seconds"))
        countdown.setSelection(prefs.getInt("countdown", 1).coerceIn(0, 3))
        root.addView(countdown)
        root.addView(text("AUDIO SOURCE", 12f, 0xFF9BA7BC.toInt()))
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        choices.addView(RadioButton(this).apply { id = 100; text = "Internal audio (Android 10+)"; setTextColor(0xFFFFFFFF.toInt()); isChecked = Build.VERSION.SDK_INT >= 29; isEnabled = Build.VERSION.SDK_INT >= 29 })
        choices.addView(RadioButton(this).apply { id = 101; text = "Microphone"; setTextColor(0xFFFFFFFF.toInt()); isChecked = Build.VERSION.SDK_INT < 29 })
        choices.addView(RadioButton(this).apply { id = 102; text = "Internal + microphone"; setTextColor(0xFFFFFFFF.toInt()); isEnabled = Build.VERSION.SDK_INT >= 29 })
        choices.setOnCheckedChangeListener { _, checked ->
            selectedMode = when (checked) { 101 -> RecordingService.MODE_MIC; 102 -> RecordingService.MODE_BOTH; else -> RecordingService.MODE_INTERNAL }
        }
        root.addView(choices)
        selectedMode = prefs.getInt("audio_mode", if (Build.VERSION.SDK_INT >= 29) RecordingService.MODE_INTERNAL else RecordingService.MODE_MIC)
        choices.check(when (selectedMode) { RecordingService.MODE_MIC -> 101; RecordingService.MODE_BOTH -> 102; else -> 100 })
        val start = Button(this).apply { text = "START RECORDING"; setOnClickListener { requestRecording() } }
        val stop = Button(this).apply { text = "STOP RECORDING"; setOnClickListener {
            val i = Intent(this@MainActivity, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            status.text = "Stopping recording…"
        } }
        root.addView(start); root.addView(stop)
        val gallery = Button(this).apply {
            text = "MY RECORDINGS"
            setOnClickListener { showRecordings() }
        }
        root.addView(gallery)
        root.addView(text("Klovv Screen automatically adjusts settings to the hardware capabilities of each Android device. Unsupported options fall back safely.", 12f, 0xFF9BA7BC.toInt()))
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
    }

    override fun onPause() {
        super.onPause()
        if (::resolution.isInitialized && ::frameRate.isInitialized && ::codec.isInitialized && ::countdown.isInitialized) {
            getSharedPreferences("glip_settings", MODE_PRIVATE).edit()
                .putInt("resolution", resolution.selectedItemPosition)
                .putInt("fps", frameRate.selectedItemPosition)
                .putInt("codec", codec.selectedItemPosition)
                .putInt("countdown", countdown.selectedItemPosition)
                .putInt("audio_mode", selectedMode).apply()
        }
    }

    private fun spinner(items: List<String>): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setPadding(4, 4, 4, 8)
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
            items.map { it.title + "\\n" + it.details })
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
                                val fps = try {
                                    val extractor = android.media.MediaExtractor()
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
                                    extractor.release()
                                    detected
                                } catch (_: Exception) { null }
                                "Resolution: ${w} × ${h}\\nFrame rate: ${fps?.let { "$it FPS" } ?: "Not available"}\\nBitrate: ${bitrate?.let { "$it kbps" } ?: "Not available"}\\n${item.details}"
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
                                            contentResolver.update(item.uri, values, null, null)
                                            status.text = "Recording renamed"
                                            showRecordings()
                                        } catch (_: Exception) { status.text = "Could not rename recording" }
                                    }
                                }.show()
                        }
                        4 -> AlertDialog.Builder(this).setMessage("Delete this recording?")
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("DELETE") { _, _ ->
                                try {
                                    contentResolver.delete(item.uri, null, null)
                                    status.text = "Recording deleted"
                                    showRecordings()
                                } catch (_: Exception) { status.text = "Could not delete recording" }
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
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
            args = arrayOf("Movies/Klovv Screen/")
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
        // Floating controls are optional: recording must still work when overlay access is denied.
        if (Build.VERSION.SDK_INT >= 26 && !Settings.canDrawOverlays(this)) {
            status.text = "Floating controls unavailable; notification controls will remain available"
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestRecording()
            else status.text = "Audio permission not granted"
        } else if (requestCode == notificationPermissionRequest) {
            // Recording can continue if notifications are denied; Android may still show the FGS task entry.
            launchCapturePermission()
        }
    }

    @Deprecated("Uses compatible Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
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
                }
                val delaySeconds = when (countdown.selectedItemPosition) { 1 -> 3; 2 -> 5; 3 -> 10; else -> 0 }
                if (delaySeconds == 0) {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                    status.text = "Recording started"
                } else {
                    object : android.os.CountDownTimer(delaySeconds * 1000L, 1000L) {
                        override fun onTick(millisUntilFinished: Long) {
                            status.text = "Recording starts in " + ((millisUntilFinished + 999) / 1000) + " seconds"
                        }
                        override fun onFinish() {
                            if (!isFinishing) {
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                                status.text = "Recording started"
                            }
                        }
                    }.start()
                }
            } else status.text = "Screen-capture permission cancelled"
        }
    }
}
