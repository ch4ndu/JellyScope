// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Runtime and delivery facts for the pinned libmpv 1.0.0 Android build. */
data class AndroidMpvCapabilityMatrix(
    val engineVersion: String = MPV_ANDROID_ENGINE_VERSION,
    val supportedContainers: Set<String> = androidMpvDeviceProfileDeclaration.containers.toSet(),
    val supportedVideoCodecs: Set<String> = androidMpvDeviceProfileDeclaration.videoCodecs.toSet(),
    val supportedAudioCodecs: Set<String> = androidMpvDeviceProfileDeclaration.audioCodecs.toSet(),
    /** Video codecs the initial controller may hand to Android MediaCodec. */
    val hardwareDelegatedVideoCodecs: Set<String> = ANDROID_MPV_HARDWARE_VIDEO_CODECS,
    val maxAudioChannels: Int = ANDROID_MPV_MAX_AUDIO_CHANNELS,
    val supportsTextSubtitles: Boolean = true,
    val supportsBitmapSubtitles: Boolean = true,
)

fun androidMpvCapabilityMatrix(): AndroidMpvCapabilityMatrix = AndroidMpvCapabilityMatrix()

/**
 * Projects the pinned declaration into the server-facing capability model.
 *
 * Decode support stays the pinned declaration. Encoded passthrough is never
 * declared: the controller may still use route-eligible encoded output at
 * runtime, but no physically qualified mpv build backs a server-facing claim,
 * so stream planning stays on decoded PCM. Display HDR facts are deliberately
 * not consulted. MediaCodec is only a source of complete coupled bounds for
 * codecs explicitly delegated to it; unknown or incomplete probe facts remain
 * unknown rather than becoming a guessed software ceiling.
 */
fun AndroidMpvCapabilityMatrix.toDeviceDecodingCapabilities(maxAudioChannels: Int = this.maxAudioChannels): DeviceDecodingCapabilities {
    val videoCodecs = supportedVideoCodecs.toList().sorted()
    val audioCodecs = supportedAudioCodecs.toList().sorted()
    val declaredHardwareCodecs =
        hardwareDelegatedVideoCodecs
            .intersect(supportedVideoCodecs)
            .toList()
            .sorted()

    return DeviceDecodingCapabilities(
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        supportsDolbyVision = false,
        supportsHdr = false,
        maxAudioChannels = maxAudioChannels,
        videoResolutionsByCodec = declaredHardwareCodecs.associateWith { VideoCodecResolution() },
        // Bit depth is not a proxy for dynamic range: mpv can decode SDR
        // 10-bit media even though this backend intentionally advertises no
        // native HDR/Dolby Vision output. Range conditions below force HDR/DV
        // through the server's SDR-compatible path.
        videoConstraintsByCodec = videoCodecs.associateWith { VideoCodecConstraints() },
        videoRangeCapabilitiesByCodec = videoCodecs.associateWith { VideoRangeCapabilities() },
        audioPassthroughCodecs = emptyList(),
        audioPassthroughMaxChannels = null,
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = supportedContainers.toList().sorted(),
                    videoCodecs = videoCodecs,
                    audioCodecs = audioCodecs,
                ),
            ),
        subtitleProfiles = androidMpvSubtitleProfiles(supportsTextSubtitles, supportsBitmapSubtitles),
        videoCodecEvidence =
            videoCodecs.associateWith {
                CodecCapabilityEvidence(
                    decodeSources = setOf(CapabilityEvidenceSource.PinnedEngineDeclaration),
                    finiteLimitSources = setOf(CapabilityEvidenceSource.Unknown),
                )
            },
        audioCodecEvidence =
            audioCodecs.associateWith {
                CodecCapabilityEvidence(
                    decodeSources = setOf(CapabilityEvidenceSource.PinnedEngineDeclaration),
                    finiteLimitSources = setOf(CapabilityEvidenceSource.DocumentedLimit),
                )
            },
    )
}

private fun androidMpvSubtitleProfiles(
    supportsTextSubtitles: Boolean,
    supportsBitmapSubtitles: Boolean,
): List<DeviceSubtitleProfile> =
    buildList {
        if (supportsTextSubtitles) {
            listOf("ass", "srt", "subrip", "vtt", "webvtt").forEach { format ->
                add(
                    DeviceSubtitleProfile(
                        format = format,
                        // The Android controller will accept only same-origin
                        // remote sidecars and project-owned local assets.
                        deliveryMethods =
                            listOf(
                                SubtitleDeliveryMethod.Embed,
                                SubtitleDeliveryMethod.External,
                                SubtitleDeliveryMethod.Encode,
                            ),
                        kind = SubtitleKind.Text,
                    ),
                )
            }
        }
        if (supportsBitmapSubtitles) {
            listOf("pgs", "pgssub").forEach { format ->
                add(
                    DeviceSubtitleProfile(
                        format = format,
                        deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                        kind = SubtitleKind.Bitmap,
                    ),
                )
            }
        }
    }

internal const val MPV_ANDROID_ENGINE_VERSION = "0.41.0"
private const val ANDROID_MPV_MAX_AUDIO_CHANNELS = 8
private val ANDROID_MPV_HARDWARE_VIDEO_CODECS =
    setOf("av1", "h264", "hevc", "mpeg2video", "mpeg4", "vp8", "vp9")
