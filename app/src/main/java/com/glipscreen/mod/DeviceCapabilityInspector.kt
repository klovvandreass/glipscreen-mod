package com.glipscreen.mod

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Display
import android.view.WindowManager

/** Encoder capability only; actual MediaProjection FPS must be measured in the saved file. */
object DeviceCapabilityInspector {
    fun inspect(context: Context, maxDimension: Int, requestedFps: Int, preferHevc: Boolean): String {
        return try {
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            val refresh = display?.refreshRate ?: 0f
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val sourceLong = maxOf(metrics.widthPixels, metrics.heightPixels)
            val scale = if (maxDimension > 0 && sourceLong > 0) maxDimension.toFloat() / sourceLong else 1f
            val targetW = ((metrics.widthPixels * scale).toInt() / 2) * 2
            val targetH = ((metrics.heightPixels * scale).toInt() / 2) * 2
            val mime = if (preferHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val fpsCandidates = if (requestedFps > 0) listOf(requestedFps) else listOf(60, 30, 24)
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
                .sortedByDescending { if (android.os.Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 1 else 0 }
            var selected: String? = null
            var hardware = false
            var selectedFps = 0
            for (codec in codecs) {
                try {
                    val caps = codec.getCapabilitiesForType(mime).videoCapabilities
                    for (fps in fpsCandidates) {
                        val w = (targetW / caps.widthAlignment) * caps.widthAlignment
                        val h = (targetH / caps.heightAlignment) * caps.heightAlignment
                        if (w >= 480 && h >= 270 && caps.areSizeAndRateSupported(w, h, fps.toDouble())) {
                            selected = codec.name
                            hardware = android.os.Build.VERSION.SDK_INT >= 29 && codec.isHardwareAccelerated
                            selectedFps = fps
                            break
                        }
                    }
                } catch (_: Exception) { }
                if (selected != null) break
            }
            val refreshText = if (refresh > 0f) "%.0f Hz".format(java.util.Locale.US, refresh) else "Unknown"
            val codecLabel = if (preferHevc) "H.265 / HEVC" else "H.264 / AVC"
            if (selected == null) {
                "$codecLabel: NOT SUPPORTED for ${targetW}×${targetH} at ${fpsCandidates.first()} FPS by listed encoders. Try Auto/lower settings."
            } else {
                val encoderType = if (hardware) "hardware encoder" else "software encoder"
                val fpsNote = if (requestedFps > 0 && refresh > 0f && requestedFps > refresh + 0.5f) " Requested FPS is above display refresh; capture cannot be assumed to reach it." else ""
                "Encoder check: SUPPORTED • $codecLabel • ${targetW}×${targetH} • target ${selectedFps} FPS • $encoderType. Display refresh: $refreshText.$fpsNote Actual captured FPS is verified only after recording."
            }
        } catch (e: Exception) {
            "Device capability check unavailable: ${e.javaClass.simpleName}"
        }
    }
}
