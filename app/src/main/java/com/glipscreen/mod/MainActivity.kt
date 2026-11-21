package com.glipscreen.mod

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 4101
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "GLIP SCREEN MOD"
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(20, 36, 20, 8)
        }
        val subtitle = TextView(this).apply {
            text = "Screen Recorder"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(20, 0, 20, 24)
        }
        status = TextView(this).apply {
            text = "Ready to record"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(20, 16, 20, 16)
        }
        val start = Button(this).apply {
            text = "Start recording"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        }
        val stop = Button(this).apply {
            text = "Stop recording"
            setOnClickListener {
                startService(Intent(this@MainActivity, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                })
                status.text = "Stopping recording…"
            }
        }
        val note = TextView(this).apply {
            text = "Video-only prototype: saves MP4 in this app's Movies folder. Audio and gallery controls are next."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(20, 24, 20, 12)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(title)
            addView(subtitle)
            addView(status)
            addView(start)
            addView(stop)
            addView(note)
        })
    }

    @Deprecated("Uses the compatible Activity result callback for broad Android support.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                    putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(RecordingService.EXTRA_RESULT_DATA, data)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                status.text = "Recording started"
            } else {
                status.text = "Screen-capture permission cancelled"
            }
        }
    }
}
