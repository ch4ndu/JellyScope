// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.CoreMedia.kCMVideoCodecType_AV1
import platform.VideoToolbox.VTIsHardwareDecodeSupported
import platform.darwin.sysctlbyname

/** Reads only the immutable launch snapshot supplied by the UIKit entry point. */
class AppleDeviceProfileProvider private constructor(
    private val displaySupportsHdr: Boolean,
    private val platformFamily: ApplePlatformFamily,
    private val deviceModelIdentifier: String,
) : DeviceProfileProvider {
    constructor(
        displaySupportsHdr: Boolean = false,
        isTvOs: Boolean = false,
    ) : this(
        displaySupportsHdr = displaySupportsHdr,
        platformFamily = if (isTvOs) ApplePlatformFamily.TvOs else ApplePlatformFamily.Ios,
        deviceModelIdentifier = appleHardwareMachineIdentifier(),
    )

    override val backendPolicy: PlayerBackendPolicy = applePlayerBackendPolicy()
    private val supportsAv1HardwareDecode by lazy { isAv1HardwareDecodeSupported() }

    private val cachedCapabilities: DeviceDecodingCapabilities by lazy {
        val videoCodecs =
            appleDeviceProfileDeclaration.videoCodecs.filter { codec ->
                codec != "av1" || supportsAv1HardwareDecode
            }

        appleAvPlayerDeviceCapabilities(
            videoCodecs = videoCodecs,
            supportsHdr = displaySupportsHdr,
            videoResolutionsByCodec =
                resolveAppleDecodeCeilings(
                    deviceModelIdentifier = deviceModelIdentifier,
                    platformFamily = platformFamily,
                    backend = PlayerBackend.AVPlayer,
                    videoCodecs = videoCodecs,
                ),
            av1DecodeEvidenceSource = CapabilityEvidenceSource.PlatformHardwareProbe,
            hasUserOverridableVideoInputEnvelope = platformFamily == ApplePlatformFamily.Ios,
        ).also { capabilities ->
            deviceProfileLogger.i {
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.Probe,
                        candidateCount = capabilities.videoCodecs.size,
                    ),
                )
            }
        }
    }

    private val cachedVlcKitCapabilities: DeviceDecodingCapabilities by lazy {
        val videoCodecs = vlcKitDeviceProfileDeclaration.videoCodecs
        vlcKitDeviceCapabilities(
            supportsAv1HardwareDecode = true,
            videoResolutionsByCodec =
                resolveAppleDecodeCeilings(
                    deviceModelIdentifier = deviceModelIdentifier,
                    platformFamily = platformFamily,
                    backend = PlayerBackend.VlcKit,
                    videoCodecs = videoCodecs,
                ),
            hasUserOverridableVideoInputEnvelope = platformFamily == ApplePlatformFamily.Ios,
        )
    }

    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
        when (backend) {
            PlayerBackend.VlcKit -> cachedVlcKitCapabilities
            PlayerBackend.Auto,
            PlayerBackend.AVPlayer,
            PlayerBackend.ExoPlayer,
            PlayerBackend.Mpv,
            PlayerBackend.LibVlc,
            -> cachedCapabilities
        }
}

private fun isAv1HardwareDecodeSupported(): Boolean =
    runCatching {
        VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1)
    }.getOrDefault(false)

@OptIn(ExperimentalForeignApi::class)
private fun appleHardwareMachineIdentifier(): String =
    memScoped {
        val size = alloc<ULongVar>()
        if (sysctlbyname("hw.machine", null, size.ptr, null, 0u) != 0 || size.value == 0UL) {
            return@memScoped "unknown"
        }
        val machine = allocArray<ByteVar>(size.value.toInt())
        if (sysctlbyname("hw.machine", machine, size.ptr, null, 0u) != 0) {
            return@memScoped "unknown"
        }
        machine.toKString()
    }

private val deviceProfileLogger = diagnosticLogger(DiagnosticTag.DeviceProfile)
