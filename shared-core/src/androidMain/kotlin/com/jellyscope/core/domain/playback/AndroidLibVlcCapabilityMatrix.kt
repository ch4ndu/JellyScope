// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import android.content.Context
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import org.videolan.libvlc.LibVLC

/** Runtime/engine facts for the pinned LibVLC Android 3.7.5 artifact. */
data class LibVlcCapabilityMatrix(
    val engineVersion: String = LIBVLC_ANDROID_VERSION,
    val supportedContainers: Set<String> = androidLibVlcDeviceProfileDeclaration.containers.toSet(),
    val supportedVideoCodecs: Set<String> = androidLibVlcDeviceProfileDeclaration.videoCodecs.toSet(),
    val supportedVideoBitDepths: Set<Int> = emptySet(),
    val supportedAudioCodecs: Set<String> = androidLibVlcDeviceProfileDeclaration.audioCodecs.toSet(),
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val maxAudioChannels: Int = 8,
    val supportsHdr: Boolean = false,
    val supportsDolbyVision: Boolean = false,
    val supportsPassthrough: Boolean = false,
    val supportsTextSubtitles: Boolean = true,
    val supportsBitmapSubtitles: Boolean = true,
)

fun androidLibVlcCapabilityMatrix(): LibVlcCapabilityMatrix =
    LibVlcCapabilityMatrix(
        maxWidth = null,
        maxHeight = null,
    )

/**
 * Applies finite MediaCodec bounds for codecs declared by LibVLC.
 *
 * Android LibVLC may delegate an intersecting codec to the platform hardware
 * path, so the active decoder probe is useful for preventing an oversized
 * source from reaching that path. Each reported bound remains independent: a
 * missing probe field stays unknown. The probe does not describe LibVLC's
 * software decoders; codecs such as AV1 with no corresponding probe fact
 * retain the engine declaration and its unknown bounds. All other LibVLC
 * capability facts remain owned by the engine declaration.
 */
internal fun DeviceDecodingCapabilities.narrowedToProbedVideoResolutions(
    probed: DeviceDecodingCapabilities,
    allowPartialProbeLimits: Boolean,
): DeviceDecodingCapabilities {
    val projectedResolutions =
        videoResolutionsByCodec.mapValues { (codec, declaredResolution) ->
            if (codec !in probed.videoCodecs || !declaredResolution.isUnknown()) {
                declaredResolution
            } else {
                probed.videoResolutionsByCodec[codec]
                    ?.takeIf { resolution ->
                        if (allowPartialProbeLimits) resolution.hasAnyFiniteLimit() else resolution.isComplete()
                    }
                    ?: declaredResolution
            }
        }
    val projectedEvidence =
        videoCodecEvidence.mapValues { (codec, declaredEvidence) ->
            if (
                codec in probed.videoCodecs &&
                videoResolutionsByCodec[codec]?.isUnknown() == true &&
                probed.videoResolutionsByCodec[codec]?.let { resolution ->
                    if (allowPartialProbeLimits) resolution.hasAnyFiniteLimit() else resolution.isComplete()
                } == true
            ) {
                declaredEvidence.copy(
                    finiteLimitSources =
                        probed.videoCodecEvidence[codec]
                            ?.finiteLimitSources
                            ?.takeIf { sources -> sources.isNotEmpty() }
                            ?: setOf(CapabilityEvidenceSource.Unknown),
                )
            } else {
                declaredEvidence
            }
        }
    return copy(
        videoResolutionsByCodec = projectedResolutions,
        videoCodecEvidence = projectedEvidence,
    )
}

private fun VideoCodecResolution.isUnknown(): Boolean =
    maxWidth == null && maxHeight == null && maxFrameArea == null && maxFrameAreaPerSecond == null

private fun VideoCodecResolution.hasAnyFiniteLimit(): Boolean =
    maxWidth != null || maxHeight != null || maxFrameArea != null || maxFrameAreaPerSecond != null

private fun VideoCodecResolution.isComplete(): Boolean =
    maxWidth != null && maxHeight != null && maxFrameArea != null && maxFrameAreaPerSecond != null

fun LibVlcCapabilityMatrix.toDeviceDecodingCapabilities(): DeviceDecodingCapabilities {
    val codecs = supportedVideoCodecs.toList().sorted()
    return DeviceDecodingCapabilities(
        videoCodecs = codecs,
        audioCodecs = supportedAudioCodecs.toList().sorted(),
        supportsDolbyVision = supportsDolbyVision,
        supportsHdr = supportsHdr,
        maxAudioChannels = maxAudioChannels,
        videoResolutionsByCodec = codecs.associateWith { VideoCodecResolution(maxWidth, maxHeight) },
        videoConstraintsByCodec = codecs.associateWith { VideoCodecConstraints(maxBitDepth = supportedVideoBitDepths.maxOrNull()) },
        videoRangeCapabilitiesByCodec = codecs.associateWith { VideoRangeCapabilities() },
        audioPassthroughCodecs = if (supportsPassthrough) supportedAudioCodecs.toList().sorted() else emptyList(),
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = supportedContainers.toList().sorted(),
                    videoCodecs = codecs,
                    audioCodecs = supportedAudioCodecs.toList().sorted(),
                ),
            ),
        subtitleProfiles =
            buildList {
                if (supportsTextSubtitles) {
                    addAll(
                        listOf("ass", "srt", "subrip", "webvtt", "vtt").map { format ->
                            DeviceSubtitleProfile(
                                format = format,
                                // External sidecars are not a reliable
                                // planner contract for LibVLC. Use direct
                                // embedded tracks or server-side burn-in.
                                deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                                kind = SubtitleKind.Text,
                            )
                        },
                    )
                }
                if (supportsBitmapSubtitles) {
                    add(
                        DeviceSubtitleProfile(
                            format = "pgs",
                            deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                            kind = SubtitleKind.Bitmap,
                        ),
                    )
                }
            },
        videoCodecEvidence =
            codecs.associateWith {
                CodecCapabilityEvidence(
                    decodeSources = setOf(CapabilityEvidenceSource.PinnedEngineDeclaration),
                    finiteLimitSources = setOf(CapabilityEvidenceSource.Unknown),
                )
            },
        audioCodecEvidence =
            supportedAudioCodecs.toList().sorted().associateWith {
                CodecCapabilityEvidence(
                    decodeSources = setOf(CapabilityEvidenceSource.PinnedEngineDeclaration),
                    finiteLimitSources = setOf(CapabilityEvidenceSource.DocumentedLimit),
                )
            },
    )
}

enum class AndroidLibVlcRuntimeAvailability {
    Available,
    Unavailable,
}

object AndroidLibVlcAvailability {
    @Volatile
    private var cachedAvailability: AndroidLibVlcRuntimeAvailability? = null

    /**
     * Probes the LibVLC runtime once per process.
     *
     * The probe builds and releases a full native engine, and both callers (the
     * playback DI factory and [AndroidDeviceProfileProvider]) reach it on the
     * playback-start path — so every LibVLC start used to construct two native
     * engines on the Fire TV target. A runtime that appears later already
     * requires an app restart to be picked up (the documented desktop policy),
     * so the first verdict is authoritative for the process.
     */
    @Synchronized
    fun check(context: Context): AndroidLibVlcRuntimeAvailability =
        cachedAvailability ?: probe(context).also { availability -> cachedAvailability = availability }

    private fun probe(context: Context): AndroidLibVlcRuntimeAvailability =
        runCatching {
            val libVlc = LibVLC(context.applicationContext, arrayListOf("--no-video-title-show"))
            libVlc.release()
            AndroidLibVlcRuntimeAvailability.Available
        }.onFailure { exception ->
            availabilityLogger.e {
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.Failed,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.LibVlc,
                        exceptionType = exception.playbackExceptionType(),
                    ),
                )
            }
        }.getOrDefault(AndroidLibVlcRuntimeAvailability.Unavailable)
}

private val availabilityLogger = diagnosticLogger(DiagnosticTag.AndroidLibVlcAvailability)

const val LIBVLC_ANDROID_VERSION = "3.7.5"
