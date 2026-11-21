package com.glipscreen.mod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import android.media.MediaScannerConnection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video-only screen capture prototype.
 * The MediaProjection callback is registered before creating the VirtualDisplay,
 * as required on recent Android versions.
 */
class RecordingService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var outputFile: File? = null
    private var videoTrack = -1
    private var muxerStarted = false
    private val stopping = AtomicBoolean(false)
    private val eosRequested = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "GLIP screen recording", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GLIP Screen MOD")
            .setContentText("Screen recording is active")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null && projection == null) {
            try {
                beginCapture(resultCode, resultData)
            } catch (error: Exception) {
                android.util.Log.e("GLIPRecorder", "Unable to start recording", error)
                requestStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        val sourceWidth = metrics.widthPixels
        val sourceHeight = metrics.heightPixels
        val scale = minOf(1f, 1920f / maxOf(sourceWidth, sourceHeight))
        val width = ((sourceWidth * scale).toInt() / 2) * 2
        val height = ((sourceHeight * scale).toInt() / 2) * 2
        val density = metrics.densityDpi

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = encoder!!.createInputSurface()
        encoder!!.start()

        val moviesDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: File(filesDir, "Movies")
        if (!moviesDir.exists() && !moviesDir.mkdirs()) {
            throw IllegalStateException("Cannot create output directory: $moviesDir")
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outputFile = File(moviesDir, "GLIP_$stamp.mp4")
        muxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Android 14+ requires a callback to be registered before createVirtualDisplay().
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                requestStop()
            }
        }, null)

        virtualDisplay = projection!!.createVirtualDisplay(
            "GLIPScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        Thread({ drainEncoder() }, "GLIP-Video-Drain").start()
    }

    private fun drainEncoder() {
        val codec = encoder ?: run {
            finishRecording()
            return
        }
        val info = MediaCodec.BufferInfo()
        var endOfStream = false
        var stopRequestedAt = 0L

        try {
            while (!endOfStream) {
                if (stopping.get()) {
                    if (stopRequestedAt == 0L) stopRequestedAt = android.os.SystemClock.elapsedRealtime()
                    if (eosRequested.compareAndSet(false, true)) {
                        try {
                            codec.signalEndOfInputStream()
                        } catch (error: Exception) {
                            android.util.Log.e("GLIPRecorder", "Unable to signal encoder EOS", error)
                            break
                        }
                    }
                }

                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopRequestedAt != 0L &&
                            android.os.SystemClock.elapsedRealtime() - stopRequestedAt > 10_000L
                        ) {
                            android.util.Log.e("GLIPRecorder", "Timed out waiting for encoder EOS")
                            break
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IllegalStateException("Encoder format changed twice")
                        videoTrack = muxer!!.addTrack(codec.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && muxerStarted &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(videoTrack, buffer, info)
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            endOfStream = true
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (error: Exception) {
            android.util.Log.e("GLIPRecorder", "Video encoding failed", error)
        } finally {
            finishRecording()
        }
    }

    private fun requestStop() {
        if (!stopping.compareAndSet(false, true)) return
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        // Keep MediaProjection alive until the encoder has drained its final frames.
        if (encoder == null) finishRecording()
    }

    @Synchronized
    private fun finishRecording() {
        if (!finished.compareAndSet(false, true)) return

        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { encoder?.stop() } catch (_: Exception) { }
        try { encoder?.release() } catch (_: Exception) { }
        encoder = null
        try {
            if (muxerStarted) muxer?.stop()
        } catch (error: Exception) {
            android.util.Log.e("GLIPRecorder", "Unable to finalize MP4", error)
        }
        try { muxer?.release() } catch (_: Exception) { }
        muxer = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null

        outputFile?.let { file ->
            if (file.exists() && file.length() > 0L && muxerStarted) {
                MediaScannerConnection.scanFile(
                    this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
                )
                android.util.Log.i("GLIPRecorder", "Recording saved: ${file.absolutePath} (${file.length()} bytes)")
            } else {
                file.delete()
                android.util.Log.e("GLIPRecorder", "Recording produced no valid MP4 file")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        requestStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.glipscreen.mod.START"
        const val ACTION_STOP = "com.glipscreen.mod.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "glip_recording"
        private const val NOTIFICATION_ID = 1001
    }
}
