package com.glipscreen.mod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.AudioPlaybackCaptureConfiguration
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.Surface
import android.view.View
import android.graphics.PixelFormat
import android.widget.Button
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RecordingService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private val paused = AtomicBoolean(false)
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var outputUri: android.net.Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val muxerLock = Object()
    private val stopping = AtomicBoolean(false)
    private val videoDone = AtomicBoolean(false)
    private val audioDone = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val videoEosSignaled = AtomicBoolean(false)
    private var drainsStarted = false
    // Surface video timestamps use the device monotonic clock; normalize them to the audio timeline.
    private var recordingStartUs = 0L
    private var pauseStartedUs = 0L
    private var totalPausedUs = 0L
    private data class PendingSample(val isVideo: Boolean, val bytes: ByteArray, val pts: Long, val flags: Int)
    private val pendingSamples = ArrayList<PendingSample>()
    private var audioMode = MODE_INTERNAL
    private var intentMaxDimension = 1920
    private var intentRequestedFps = 0
    private var preferHevc = false
    private var playbackRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var floatingPanel: View? = null
    private var windowManager: WindowManager? = null
    private var floatingPauseButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "GLIP screen recording", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { requestStop(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pauseCapture(); return START_NOT_STICKY }
            ACTION_RESUME -> { resumeCapture(); return START_NOT_STICKY }
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GLIP Screen MOD").setContentText("Recording • elapsed time")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setWhen(System.currentTimeMillis()).setUsesChronometer(true)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else { @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA) }
        audioMode = intent?.getIntExtra(EXTRA_AUDIO_MODE, MODE_INTERNAL) ?: MODE_INTERNAL
        intentMaxDimension = intent?.getIntExtra(EXTRA_MAX_DIMENSION, 1920)?.takeIf { it > 0 } ?: 1920
        intentRequestedFps = intent?.getIntExtra(EXTRA_REQUESTED_FPS, 0) ?: 0
        preferHevc = intent?.getBooleanExtra(EXTRA_PREFER_HEVC, false) ?: false
        if (resultCode != 0 && data != null && projection == null) {
            try { beginCapture(resultCode, data) }
            catch (e: Exception) {
                android.util.Log.e("GLIPRecorder", "Start failed", e)
                requestStop()
                if (!drainsStarted) {
                    videoDone.set(true)
                    audioDone.set(true)
                    maybeFinish()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val scale = minOf(1f, intentMaxDimension.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels))
        val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2) * 2

        val wantedMime = if (preferHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val allCodecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        fun findEncoder(mime: String): MediaCodecInfo? {
            val candidates = allCodecs.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
            }.sortedByDescending { if (Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 1 else 0 }
            return candidates.firstOrNull { info ->
                try {
                    val caps = info.getCapabilitiesForType(mime).videoCapabilities
                    val w = (width / caps.widthAlignment) * caps.widthAlignment
                    val h = (height / caps.heightAlignment) * caps.heightAlignment
                    caps.areSizeAndRateSupported(w, h, 24.0)
                } catch (_: Exception) { false }
            }
        }
        val codecInfo = (findEncoder(wantedMime)
            ?: (if (wantedMime != MediaFormat.MIMETYPE_VIDEO_AVC) findEncoder(MediaFormat.MIMETYPE_VIDEO_AVC) else null))
            ?: throw IllegalStateException("No compatible video encoder for this device")
        val mime = if (codecInfo.supportedTypes.any { it.equals(wantedMime, true) }) wantedMime else MediaFormat.MIMETYPE_VIDEO_AVC
        val videoCaps = codecInfo.getCapabilitiesForType(mime).videoCapabilities
        val alignedWidth = (width / videoCaps.widthAlignment) * videoCaps.widthAlignment
        val alignedHeight = (height / videoCaps.heightAlignment) * videoCaps.heightAlignment
        val fpsCandidates = if (intentRequestedFps > 0) listOf(intentRequestedFps, 60, 30, 24).distinct() else listOf(60, 30, 24)
        val fps = fpsCandidates.firstOrNull { it >= 24 && videoCaps.areSizeAndRateSupported(alignedWidth, alignedHeight, it.toDouble()) }
            ?: throw IllegalStateException("This device cannot encode the selected screen size")
        val pixelRatio = (alignedWidth.toLong() * alignedHeight.toLong()).toDouble() / (1920.0 * 1080.0)
        val bitrate = (10_000_000.0 * pixelRatio * fps / 60.0).toLong().coerceIn(1_000_000L, 40_000_000L).toInt()
        val encoderCaps = codecInfo.getCapabilitiesForType(mime).encoderCapabilities
        val vf = MediaFormat.createVideoFormat(mime, alignedWidth, alignedHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Prefer a predictable real-time bitrate when the hardware encoder supports CBR.
            if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        videoEncoder = MediaCodec.createByCodecName(codecInfo.name).apply {
            configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = videoEncoder!!.createInputSurface()
        inputSurface = surface
        videoEncoder!!.start()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "GLIP_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GLIP MOD")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create output video")
        outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
            ?: throw IllegalStateException("Cannot open output video")
        muxer = MediaMuxer(outputDescriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        setupAudio()
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { requestStop() }
        }, null)
        recordingStartUs = System.nanoTime() / 1_000L
        virtualDisplay = projection!!.createVirtualDisplay("GLIPScreenCapture", alignedWidth, alignedHeight,
            metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        showFloatingControls()
        thread(name = "GLIP-Video-Drain") { drainVideo() }
        thread(name = "GLIP-Audio-Record") { recordAudio() }
        thread(name = "GLIP-Audio-Drain") { drainAudio() }
        drainsStarted = true
    }

    private fun setupAudio() {
        val rate = 44100
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IllegalStateException("This device does not support the selected audio format")
        val bufferSize = maxOf(minBuffer * 2, 16384)
        val sources = mutableListOf<AudioRecord>()
        if (audioMode == MODE_INTERNAL || audioMode == MODE_BOTH) {
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Internal audio needs Android 10 or newer")
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            playbackRecord = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(bufferSize).build()
            if (playbackRecord?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Internal audio capture could not be initialized")
            sources.add(playbackRecord!!)
        }
        if (audioMode == MODE_MIC || audioMode == MODE_BOTH) {
            micRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(bufferSize).build()
            if (micRecord?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone could not be initialized")
            sources.add(micRecord!!)
        }
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        try {
            sources.forEach { source ->
                source.startRecording()
                if (source.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Audio input failed to start")
                }
            }
        } catch (e: Exception) {
            sources.forEach { try { it.stop() } catch (_: Exception) { }; try { it.release() } catch (_: Exception) { } }
            playbackRecord = null
            micRecord = null
            try { audioEncoder?.stop() } catch (_: Exception) { }
            try { audioEncoder?.release() } catch (_: Exception) { }
            audioEncoder = null
            throw e
        }
    }

    private fun addTrack(isVideo: Boolean, format: MediaFormat) {
        synchronized(muxerLock) {
            if (isVideo) videoTrack = muxer!!.addTrack(format) else audioTrack = muxer!!.addTrack(format)
            if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
                muxer!!.start()
                muxerStarted = true
                // Interleave the buffered startup samples by presentation timestamp before writing.
                for (sample in pendingSamples.sortedBy { it.pts }) {
                    val track = if (sample.isVideo) videoTrack else audioTrack
                    val data = java.nio.ByteBuffer.wrap(sample.bytes)
                    val info = MediaCodec.BufferInfo().apply { set(0, sample.bytes.size, sample.pts, sample.flags) }
                    muxer!!.writeSampleData(track, data, info)
                }
                pendingSamples.clear()
                muxerLock.notifyAll()
            }
        }
    }

    private fun writeSample(isVideo: Boolean, buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (info.size <= 0) return
            val copy = ByteArray(info.size)
            val duplicate = buffer.duplicate()
            duplicate.position(info.offset)
            duplicate.limit(info.offset + info.size)
            duplicate.get(copy)
            // Video Surface timestamps are absolute monotonic time; AAC timestamps start at zero.
            // Put both tracks on one recording-relative timeline so MP4 duration is valid.
            val normalizedPts = if (isVideo) (info.presentationTimeUs - recordingStartUs - totalPausedUs).coerceAtLeast(0L)
                else info.presentationTimeUs
            if (!muxerStarted) {
                pendingSamples.add(PendingSample(isVideo, copy, normalizedPts, info.flags))
                return
            }
            val track = if (isVideo) videoTrack else audioTrack
            if (track >= 0) {
                val data = java.nio.ByteBuffer.wrap(copy)
                val sampleInfo = MediaCodec.BufferInfo().apply { set(0, copy.size, normalizedPts, info.flags) }
                muxer!!.writeSampleData(track, data, sampleInfo)
            }
        }
    }

    private fun drainVideo() {
        val codec = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var eos = false
        try {
            while (!eos) {
                if (stopping.get() && videoEosSignaled.compareAndSet(false, true)) {
                    try { codec.signalEndOfInputStream() } catch (_: Exception) { }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(true, codec.outputFormat)
                    index >= 0 -> {
                        val b = codec.getOutputBuffer(index)
                        if (b != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            b.position(info.offset); b.limit(info.offset + info.size); writeSample(true, b, info)
                        }
                        eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: Exception) { android.util.Log.e("GLIPRecorder", "Video drain failed", e) }
        finally { videoDone.set(true); maybeFinish() }
    }

    private fun recordAudio() {
        val codec = audioEncoder ?: return
        val rate = 44100
        val playbackSamples = ShortArray(2048)
        val micSamples = ShortArray(2048)
        var samples: Long = 0
        try {
            while (!stopping.get()) {
                // Reserve an encoder input slot before reading PCM. This avoids consuming
                // microphone/game audio and then throwing it away when the codec is busy.
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex < 0) continue
                val input = codec.getInputBuffer(inputIndex)
                if (input == null) {
                    codec.queueInputBuffer(inputIndex, 0, 0, samples * 1_000_000L / rate, 0)
                    continue
                }
                input.clear()
                val playback = playbackRecord
                val mic = micRecord
                var count = 0
                var playbackCount = 0
                var micCount = 0
                while (count <= 0 && !stopping.get()) {
                    if (paused.get()) {
                        if (playback != null) playback.read(playbackSamples, 0, playbackSamples.size, AudioRecord.READ_NON_BLOCKING)
                        if (mic != null) mic.read(micSamples, 0, micSamples.size, AudioRecord.READ_NON_BLOCKING)
                        Thread.sleep(10)
                        continue
                    }
                    playbackCount = if (playback != null) playback.read(
                        playbackSamples, 0, playbackSamples.size, AudioRecord.READ_NON_BLOCKING
                    ).coerceAtLeast(0) else 0
                    micCount = if (mic != null) mic.read(
                        micSamples, 0, micSamples.size, AudioRecord.READ_NON_BLOCKING
                    ).coerceAtLeast(0) else 0
                    count = when {
                        playback != null && mic != null -> minOf(playbackCount, micCount)
                        playback != null -> playbackCount
                        else -> micCount
                    }
                    count -= count % 2
                    if (count <= 0) Thread.sleep(2)
                }
                if (stopping.get()) {
                    codec.queueInputBuffer(inputIndex, 0, 0, samples * 1_000_000L / rate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                count = minOf(count, input.remaining() / 2)
                count -= count % 2
                if (count <= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, samples * 1_000_000L / rate, 0)
                    continue
                }
                for (i in 0 until count) {
                    val value = when {
                        playback != null && mic != null ->
                            (playbackSamples[i].toInt() + micSamples[i].toInt()) / 2
                        playback != null -> playbackSamples[i].toInt()
                        else -> micSamples[i].toInt()
                    }
                    input.putShort(value.coerceIn(-32768, 32767).toShort())
                }
                val pts = samples * 1_000_000L / rate
                codec.queueInputBuffer(inputIndex, 0, count * 2, pts, 0)
                samples += count / 2
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            android.util.Log.e("GLIPRecorder", "Audio capture failed", e)
        } finally {
            try { playbackRecord?.stop() } catch (_: Exception) { }
            try { micRecord?.stop() } catch (_: Exception) { }
            try { playbackRecord?.release() } catch (_: Exception) { }
            playbackRecord = null
            try { micRecord?.release() } catch (_: Exception) { }
            micRecord = null
            try {
                var queued = false
                while (!queued && !finished.get()) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        codec.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / rate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GLIPRecorder", "Could not signal audio EOS", e)
            }
        }
    }

    private fun drainAudio() {
        val codec = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var eos = false
        try {
            while (!eos) {
                val index = codec.dequeueOutputBuffer(info, 10000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(false, codec.outputFormat)
                    index >= 0 -> {
                        val b = codec.getOutputBuffer(index)
                        if (b != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            b.position(info.offset); b.limit(info.offset + info.size); writeSample(false, b, info)
                        }
                        eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: Exception) { android.util.Log.e("GLIPRecorder", "Audio encoder drain failed", e) }
        finally { audioDone.set(true); maybeFinish() }
    }

    private fun pauseCapture() {
        if (stopping.get() || !paused.compareAndSet(false, true)) return
        pauseStartedUs = System.nanoTime() / 1_000L
        try { virtualDisplay?.setSurface(null) } catch (e: Exception) { android.util.Log.e("GLIPRecorder", "Pause failed", e) }
        updateNotification()
        updateFloatingControls()
    }

    private fun resumeCapture() {
        if (stopping.get() || !paused.compareAndSet(true, false)) return
        if (pauseStartedUs > 0L) totalPausedUs += (System.nanoTime() / 1_000L - pauseStartedUs).coerceAtLeast(0L)
        pauseStartedUs = 0L
        try { virtualDisplay?.setSurface(inputSurface) } catch (e: Exception) { paused.set(true); android.util.Log.e("GLIPRecorder", "Resume failed", e) }
        updateNotification()
        updateFloatingControls()
    }

    private fun updateNotification() {
        val action = if (paused.get()) ACTION_RESUME else ACTION_PAUSE
        val label = if (paused.get()) "Resume" else "Pause"
        val toggle = PendingIntent.getService(this, 1002, Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1003, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GLIP Screen MOD")
            .setContentText(if (paused.get()) "Recording paused" else "Recording • elapsed time")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setWhen(System.currentTimeMillis()).setUsesChronometer(!paused.get()).setOngoing(true)
            .addAction(Notification.Action.Builder(null, label, toggle).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun showFloatingControls() {
        if (Build.VERSION.SDK_INT < 26 || !android.provider.Settings.canDrawOverlays(this)) return
        try {
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 10); setBackgroundColor(0xEE111827.toInt()) }
            val title = android.widget.TextView(this).apply { text = "GLIP • RECORDING"; textSize = 12f; setTextColor(0xFF80B7FF.toInt()); setPadding(8, 4, 8, 8) }
            floatingPauseButton = Button(this).apply { text = "PAUSE"; setOnClickListener {
                val action = if (paused.get()) ACTION_RESUME else ACTION_PAUSE
                startService(Intent(this@RecordingService, RecordingService::class.java).setAction(action))
            } }
            val stop = Button(this).apply { text = "STOP"; setOnClickListener { startService(Intent(this@RecordingService, RecordingService::class.java).setAction(ACTION_STOP)) } }
            panel.addView(title); panel.addView(floatingPauseButton); panel.addView(stop)
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL; x = 12 }
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager?.addView(panel, params)
            floatingPanel = panel
        } catch (e: Exception) { android.util.Log.e("GLIPRecorder", "Floating controls unavailable", e) }
    }
    private fun updateFloatingControls() { floatingPauseButton?.text = if (paused.get()) "RESUME" else "PAUSE" }
    private fun removeFloatingControls() {
        try { val panel = floatingPanel; if (panel != null) windowManager?.removeView(panel) } catch (_: Exception) { }
        floatingPanel = null; floatingPauseButton = null; windowManager = null
    }
    private fun requestStop() {
        if (!stopping.compareAndSet(false, true)) return
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        inputSurface = null
        try { playbackRecord?.stop() } catch (_: Exception) { }
        try { micRecord?.stop() } catch (_: Exception) { }
        if (!drainsStarted) {
            videoDone.set(true)
            audioDone.set(true)
            maybeFinish()
        }
    }

    private fun maybeFinish() {
        if (videoDone.get() && audioDone.get()) finishRecording()
    }

    @Synchronized private fun finishRecording() {
        if (!finished.compareAndSet(false, true)) return
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { videoEncoder?.stop() } catch (_: Exception) { }
        try { videoEncoder?.release() } catch (_: Exception) { }
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Exception) { }
        try { audioEncoder?.release() } catch (_: Exception) { }
        audioEncoder = null
        try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { android.util.Log.e("GLIPRecorder", "Muxer finalization failed", e) }
        try { muxer?.release() } catch (_: Exception) { }
        muxer = null
        try { playbackRecord?.stop() } catch (_: Exception) { }
        try { playbackRecord?.release() } catch (_: Exception) { }
        playbackRecord = null
        try { micRecord?.stop() } catch (_: Exception) { }
        try { micRecord?.release() } catch (_: Exception) { }
        micRecord = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        try { outputDescriptor?.close() } catch (_: Exception) { }
        outputDescriptor = null
        outputUri?.let { uri ->
            if (muxerStarted) {
                if (Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                android.util.Log.i("GLIPRecorder", "Saved: $uri")
            } else contentResolver.delete(uri, null, null)
        }
        removeFloatingControls()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { requestStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.glipscreen.mod.START"
        const val ACTION_STOP = "com.glipscreen.mod.STOP"
        const val ACTION_PAUSE = "com.glipscreen.mod.PAUSE"
        const val ACTION_RESUME = "com.glipscreen.mod.RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_MAX_DIMENSION = "max_dimension"
        const val EXTRA_REQUESTED_FPS = "requested_fps"
        const val EXTRA_PREFER_HEVC = "prefer_hevc"
        const val MODE_INTERNAL = 0
        const val MODE_MIC = 1
        const val MODE_BOTH = 2
        private const val CHANNEL_ID = "glip_recording"
        private const val NOTIFICATION_ID = 1001
    }
}