package com.glipscreen.mod

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 4101
    private val audioPermissionRequest = 4102
    private lateinit var status: TextView
    private var selectedMode = RecordingService.MODE_INTERNAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply { text = "GLIP SCREEN MOD"; textSize = 26f; gravity = Gravity.CENTER; setPadding(20, 36, 20, 8) }
        val subtitle = TextView(this).apply { text = "Screen Recorder"; textSize = 16f; gravity = Gravity.CENTER; setPadding(20, 0, 20, 20) }
        status = TextView(this).apply { text = "Ready to record"; textSize = 14f; gravity = Gravity.CENTER; setPadding(20, 16, 20, 16) }
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val internal = RadioButton(this).apply { id = 100; text = "Internal audio (Android 10+)"; isChecked = true }
        val mic = RadioButton(this).apply { id = 101; text = "Microphone" }
        val both = RadioButton(this).apply { id = 102; text = "Internal + microphone" }
        choices.addView(internal); choices.addView(mic); choices.addView(both)
        choices.setOnCheckedChangeListener { _, checked ->
            selectedMode = when (checked) { 101 -> RecordingService.MODE_MIC; 102 -> RecordingService.MODE_BOTH; else -> RecordingService.MODE_INTERNAL }
        }
        val start = Button(this).apply { text = "Start recording"; setOnClickListener { requestRecording() } }
        val stop = Button(this).apply {
            text = "Stop recording"
            setOnClickListener {
                val i = Intent(this@MainActivity, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                status.text = "Stopping recording…"
            }
        }
        val note = TextView(this).apply {
            text = "Choose an audio source before recording. Internal audio depends on Android version and whether the app allows capture."
            textSize = 12f; gravity = Gravity.CENTER; setPadding(20, 20, 20, 12)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
            addView(title); addView(subtitle); addView(status); addView(choices); addView(start); addView(stop); addView(note)
        })
    }

    private fun requestRecording() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestRecording()
            else status.text = "Microphone/audio permission is required"
        }
    }

    @Deprecated("Uses compatible Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                    putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(RecordingService.EXTRA_RESULT_DATA, data)
                    putExtra(RecordingService.EXTRA_AUDIO_MODE, selectedMode)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                status.text = "Recording started"
            } else status.text = "Screen-capture permission cancelled"
        }
    }
}