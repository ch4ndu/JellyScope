// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import com.jellyscope.core.domain.playback.AndroidTvMpvVideoOutput

internal enum class AndroidMpvDeviceClass {
    Mobile,
    Television,
    Emulator,
}

internal data class AndroidMpvDeviceFacts(
    val isTelevision: Boolean,
    val isEmulator: Boolean,
)

internal data class AndroidMpvEnginePolicy(
    val deviceClass: AndroidMpvDeviceClass,
    val videoOutput: String,
    val hardwareDecoder: String,
    val profile: String? = null,
    val cacheSeconds: Int = 10,
    val maxForwardCacheBytes: Long = 64L * 1024L * 1024L,
    val maxBackwardCacheBytes: Long = 16L * 1024L * 1024L,
    val keepOpen: String = "always",
) {
    val hardwareDecoderCodecs: String = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"
    val cacheEnabled: String = "yes"

    fun options(caBundlePath: String): Map<String, String> =
        linkedMapOf(
            "config" to "no",
            "load-auto-profiles" to "no",
            "scripts" to "",
            "osc" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "ytdl" to "no",
            "cookies" to "no",
            "autoload-files" to "no",
            "vo" to videoOutput,
            "gpu-context" to "android",
            "opengl-es" to "yes",
            "hwdec" to hardwareDecoder,
            "hwdec-codecs" to hardwareDecoderCodecs,
            "tls-verify" to "yes",
            "tls-ca-file" to caBundlePath,
            "cache" to cacheEnabled,
            "cache-secs" to cacheSeconds.toString(),
            "cache-pause-initial" to "yes",
            "cache-pause" to "yes",
            "demuxer-max-bytes" to "${maxForwardCacheBytes / (1024L * 1024L)}MiB",
            "demuxer-max-back-bytes" to "${maxBackwardCacheBytes / (1024L * 1024L)}MiB",
            "idle" to "yes",
            "keep-open" to keepOpen,
            "force-window" to "no",
            "audio-client-name" to "JellyScope",
            "alang" to "",
            "slang" to "",
            "sid" to "no",
            "aid" to "auto",
            "sub-auto" to "no",
            "audio-file-auto" to "no",
            "sub-file-paths" to "",
        ).apply {
            profile?.let { value -> put("profile", value) }
        }
}

internal fun androidMpvDeviceFacts(context: Context): AndroidMpvDeviceFacts {
    val applicationContext = context.applicationContext
    val uiModeManager = applicationContext.getSystemService(UiModeManager::class.java)
    val televisionMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val leanback = applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return AndroidMpvDeviceFacts(
        isTelevision = televisionMode || leanback,
        isEmulator = isAndroidEmulator(),
    )
}

internal fun androidMpvEnginePolicy(
    facts: AndroidMpvDeviceFacts,
    tvVideoOutput: AndroidTvMpvVideoOutput = AndroidTvMpvVideoOutput.Gpu,
): AndroidMpvEnginePolicy {
    val deviceClass =
        when {
            facts.isTelevision -> AndroidMpvDeviceClass.Television
            facts.isEmulator -> AndroidMpvDeviceClass.Emulator
            else -> AndroidMpvDeviceClass.Mobile
        }
    return AndroidMpvEnginePolicy(
        deviceClass = deviceClass,
        // TV defaults to vo=gpu, not gpu-next: with zero-copy mediacodec the Tegra GL
        // driver (Shield) rejects libplacebo's program link when
        // samplerExternalOES appears in both shader stages, so gpu-next never
        // renders a frame there. mpv's classic renderer is the long-standing
        // mpv-android default and handles external samplers correctly. The
        // copy/software classes never import external samplers and keep the
        // gpu-next baseline.
        videoOutput =
            when (deviceClass) {
                AndroidMpvDeviceClass.Television ->
                    when (tvVideoOutput) {
                        AndroidTvMpvVideoOutput.Gpu -> "gpu"
                        AndroidTvMpvVideoOutput.DirectMediaCodec -> "mediacodec_embed"
                    }
                AndroidMpvDeviceClass.Emulator -> "gpu-next"
                AndroidMpvDeviceClass.Mobile -> "gpu-next"
            },
        hardwareDecoder =
            when (deviceClass) {
                AndroidMpvDeviceClass.Television -> "mediacodec"
                AndroidMpvDeviceClass.Emulator -> "no"
                AndroidMpvDeviceClass.Mobile -> "mediacodec-copy"
            },
        profile = "fast".takeIf { deviceClass == AndroidMpvDeviceClass.Television },
    )
}

private fun isAndroidEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val hardware = Build.HARDWARE.lowercase()
    return fingerprint.startsWith("generic") ||
        fingerprint.contains("emulator") ||
        model.contains("google_sdk") ||
        model.contains("emulator") ||
        model.contains("android sdk built for") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu")
}
