package com.glipscreen.mod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RecordingService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
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
    private data class PendingSample(val isVideo: Boolean, val bytes: ByteArray, val pts: Long, val flags: Int)
    private val pendingSamples = ArrayList<PendingSample>()
    private var audioMode = MODE_INTERNAL
    private var playbackRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "GLIP screen recording", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { requestStop(); return START_NOT_STICKY }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GLIP Screen MOD").setContentText("Screen recording is active")
            .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else { @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA) }
        audioMode = intent?.getIntExtra(EXTRA_AUDIO_MODE, MODE_INTERNAL) ?: MODE_INTERNAL
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
        val scale = minOf(1f, 1920f / maxOf(metrics.widthPixels, metrics.heightPixels))
        val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2) * 2

        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = videoEncoder!!.createInputSurface()
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
        virtualDisplay = projection!!.createVirtualDisplay("GLIPScreenCapture", width, height,
            metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        thread(name = "GLIP-Video-Drain") { drainVideo() }
        thread(name = "GLIP-Audio-Record") { recordAudio() }
        thread(name = "GLIP-Audio-Drain") { drainAudio() }
        drainsStarted = true
    }

    private fun setupAudio() {
        val rate = 44100
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuffer, rate * 2 / 2)
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
            sources.add(playbackRecord!!)
        }
        if (audioMode == MODE_MIC || audioMode == MODE_BOTH) {
            micRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(bufferSize).build()
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
        sources.forEach { it.startRecording() }
    }

    private fun addTrack(isVideo: Boolean, format: MediaFormat) {
        synchronized(muxerLock) {
            if (isVideo) videoTrack = muxer!!.addTrack(format) else audioTrack = muxer!!.addTrack(format)
            if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
                muxer!!.start()
                muxerStarted = true
                for (sample in pendingSamples) {
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
            if (!muxerStarted) {
                pendingSamples.add(PendingSample(isVideo, copy, info.presentationTimeUs, info.flags))
                return
            }
            val track = if (isVideo) videoTrack else audioTrack
            if (track >= 0) {
                val data = java.nio.ByteBuffer.wrap(copy)
                val sampleInfo = MediaCodec.BufferInfo().apply { set(0, copy.size, info.presentationTimeUs, info.flags) }
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
                val playback = playbackRecord
                val mic = micRecord
                val pCount = if (playback != null) playback.read(
                    playbackSamples, 0, playbackSamples.size, AudioRecord.READ_NON_BLOCKING
                ) else 0
                val mCount = if (mic != null) mic.read(
                    micSamples, 0, micSamples.size, AudioRecord.READ_NON_BLOCKING
                ) else 0
                var count = when {
                    playback != null && mic != null -> minOf(pCount.coerceAtLeast(0), mCount.coerceAtLeast(0))
                    playback != null -> pCount.coerceAtLeast(0)
                    else -> mCount.coerceAtLeast(0)
                }
                count -= count % 2
                if (count <= 0) {
                    Thread.sleep(2)
                    continue
                }
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex < 0) continue
                val input = codec.getInputBuffer(inputIndex)
                if (input == null) {
                    codec.queueInputBuffer(inputIndex, 0, 0, samples * 1_000_000L / rate, 0)
                    continue
                }
                input.clear()
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

    private fun requestStop() {
        if (!stopping.compareAndSet(false, true)) return
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { requestStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.glipscreen.mod.START"
        const val ACTION_STOP = "com.glipscreen.mod.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val MODE_INTERNAL = 0
        const val MODE_MIC = 1
        const val MODE_BOTH = 2
        private const val CHANNEL_ID = "glip_recording"
        private const val NOTIFICATION_ID = 1001
    }
}