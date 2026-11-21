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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "GLIP SCREEN MOD\nPremium Screen Recorder"
            textSize = 23f
            gravity = Gravity.CENTER
            setPadding(24, 48, 24, 24)
        }
        val description = TextView(this).apply {
            text = "Project foundation. Screen recording engine is not implemented yet."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(24, 8, 24, 24)
        }
        val start = Button(this).apply {
            text = "Test screen-capture permission"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(title)
            addView(description)
            addView(start)
        })
    }
}
