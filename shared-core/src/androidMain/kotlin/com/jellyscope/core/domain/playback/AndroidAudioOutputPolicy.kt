// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build

internal enum class AndroidAudioSinkType {
    Hdmi,
    Other,
}

internal data class AndroidAudioSinkCapabilities(
    val encodings: Set<Int>,
    val maxChannelCount: Int,
    val type: AndroidAudioSinkType = AndroidAudioSinkType.Other,
)

/**
 * One media-route observation. [exclusive] holds only for the single API-33
 * routed device, or a plugged HDMI sink with no competing Bluetooth, wired,
 * USB, line, or hearing-aid output; a connected sink alone is not proof of
 * active delivery.
 */
internal data class AndroidAudioRoute(
    val sinks: List<AndroidAudioSinkCapabilities>,
    val television: Boolean = false,
    val exclusive: Boolean = false,
)

internal data class AudioRouteCapabilities(
    val passthroughCodecs: List<String> = emptyList(),
    val maxAudioChannels: Int? = null,
    val passthroughMaxAudioChannels: Int? = null,
)

/** Route-derived output for the mpv controller; server passthrough claims require separate qualification. */
internal data class AndroidMpvAudioRouteOutput(
    val encodedCodecs: List<String> = emptyList(),
    val stereoPcm: Boolean = false,
)

/** Platform media route; observation callbacks arrive on the main thread. */
internal interface AndroidAudioRouteSource {
    fun route(): AndroidAudioRoute

    /** Starts route-change callbacks and returns their closer, or null when changes cannot be observed. */
    fun observe(onChanged: () -> Unit): AutoCloseable?
}

/**
 * mpv may send encoded audio only on a television whose exclusive route is
 * HDMI. Only E-AC3 is enabled until other carriers are qualified. Unknown,
 * mobile, Bluetooth, wired, and ambiguous routes keep mpv's PCM defaults; a
 * confirmed stereo HDMI sink receives stereo PCM rather than a multichannel
 * layout for the platform mixer to fold down.
 */
internal fun AndroidAudioRoute.mpvOutput(): AndroidMpvAudioRouteOutput {
    val sink =
        sinks.singleOrNull()?.takeIf { candidate -> television && exclusive && candidate.type == AndroidAudioSinkType.Hdmi }
            ?: return AndroidMpvAudioRouteOutput()
    val encodedCodecs =
        sink.encodings
            .flatMap { encoding -> encoding.toJellyfinPassthroughCodecs() }
            .filter(ANDROID_MPV_ENCODED_AUDIO_CODECS::contains)
            .distinct()
            .sorted()
    return AndroidMpvAudioRouteOutput(
        encodedCodecs = encodedCodecs,
        stereoPcm = sink.maxChannelCount <= SAFE_STEREO_CHANNEL_COUNT,
    )
}

internal fun audioRouteCapabilities(sinks: List<AndroidAudioSinkCapabilities>): AudioRouteCapabilities {
    if (sinks.isEmpty()) {
        return AudioRouteCapabilities(maxAudioChannels = SAFE_STEREO_CHANNEL_COUNT)
    }
    val passthroughCodecs =
        sinks
            .map { sink -> sink.encodings.flatMap { encoding -> encoding.toJellyfinPassthroughCodecs() }.toSet() }
            .reduce { common, codecs -> common intersect codecs }
            .toList()
            .sorted()
    val maxAudioChannels =
        sinks.minOf { sink -> sink.maxChannelCount.takeIf { channels -> channels > 0 } ?: SAFE_STEREO_CHANNEL_COUNT }
    return AudioRouteCapabilities(
        passthroughCodecs = passthroughCodecs,
        maxAudioChannels = maxAudioChannels,
        passthroughMaxAudioChannels = maxAudioChannels.takeIf { passthroughCodecs.isNotEmpty() },
    )
}

internal fun legacyHdmiAudioSink(
    plugState: Int,
    encodings: IntArray?,
    maxChannelCount: Int,
): AndroidAudioSinkCapabilities? =
    if (plugState == 1) {
        AndroidAudioSinkCapabilities(
            encodings = encodings?.toSet().orEmpty(),
            maxChannelCount = maxChannelCount.takeIf { channels -> channels > 0 } ?: SAFE_STEREO_CHANNEL_COUNT,
            type = AndroidAudioSinkType.Hdmi,
        )
    } else {
        null
    }

internal fun isAndroidTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(UiModeManager::class.java)
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

internal class SystemAndroidAudioRouteSource(
    private val appContext: Context,
) : AndroidAudioRouteSource {
    private val television by lazy { isAndroidTelevision(appContext) }

    override fun route(): AndroidAudioRoute {
        val audioManager =
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return AndroidAudioRoute(sinks = emptyList(), television = television)
        val routedDevices =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val mediaAttributes =
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                runCatching { audioManager.getAudioDevicesForAttributes(mediaAttributes) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        if (routedDevices.isNotEmpty()) {
            return AndroidAudioRoute(
                sinks = routedDevices.map { device -> device.toAudioSinkCapabilities() },
                television = television,
                exclusive = routedDevices.size == 1,
            )
        }
        val hdmiSink =
            legacyHdmiAudioSink()
                ?: return AndroidAudioRoute(sinks = emptyList(), television = television)
        val competingOutput =
            runCatching {
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { device -> device.type in competingOutputTypes }
            }.getOrDefault(true)
        return AndroidAudioRoute(sinks = listOf(hdmiSink), television = television, exclusive = !competingOutput)
    }

    /** Only television output depends on the route, so other devices register nothing. */
    override fun observe(onChanged: () -> Unit): AutoCloseable? {
        if (!television) return null
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val deviceCallback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onChanged()

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onChanged()
            }
        val hdmiReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) = onChanged()
            }
        val closer =
            AutoCloseable {
                runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
                runCatching { appContext.unregisterReceiver(hdmiReceiver) }
            }
        return runCatching {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            val filter = IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(hdmiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(hdmiReceiver, filter)
            }
            closer
        }.getOrElse {
            closer.close()
            null
        }
    }

    private fun legacyHdmiAudioSink(): AndroidAudioSinkCapabilities? {
        val intent =
            runCatching {
                appContext.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            }.getOrNull()
                ?: return null
        return legacyHdmiAudioSink(
            plugState = intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0),
            encodings = intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS),
            maxChannelCount = intent.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, SAFE_STEREO_CHANNEL_COUNT),
        )
    }
}

private fun AudioDeviceInfo.toAudioSinkCapabilities(): AndroidAudioSinkCapabilities =
    AndroidAudioSinkCapabilities(
        encodings = encodings.toSet(),
        maxChannelCount = channelCounts.maxOrNull() ?: SAFE_STEREO_CHANNEL_COUNT,
        type =
            when (type) {
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                -> AndroidAudioSinkType.Hdmi
                else -> AndroidAudioSinkType.Other
            },
    )

private fun Int.toJellyfinPassthroughCodecs(): List<String> =
    when (this) {
        AudioFormat.ENCODING_AC3 -> listOf("ac3")
        AudioFormat.ENCODING_E_AC3 -> listOf("eac3")
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_DTS_HD,
        -> listOf("dts", "dca")
        AudioFormat.ENCODING_DOLBY_TRUEHD -> listOf("truehd")
        else -> emptyList()
    }

// Compile-time constants: platforms older than a type's API level never report it.
@SuppressLint("InlinedApi")
private val competingOutputTypes =
    setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

private val ANDROID_MPV_ENCODED_AUDIO_CODECS = setOf("eac3")
private const val SAFE_STEREO_CHANNEL_COUNT = 2
