// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Apple product family used to select the conservative decode fallback. */
internal enum class ApplePlatformFamily {
    Ios,
    TvOs,
}

/**
 * Returns the known ceiling for every advertised Apple video codec.
 *
 * This lookup is deliberately pure so host tests can cover a model identifier
 * without running on Apple hardware. The appleMain provider reads `hw.machine`
 * once, then passes it here while building each immutable capability snapshot.
 *
 * The iOS fallback is the iOS 16 deployment floor: iPhone 8 (A11) supports
 * HEVC and H.264 video and 4K60 capture according to Apple Support
 * (https://support.apple.com/en-us/111976). It therefore remains 4K30 for both
 * codecs rather than under-claiming every unknown future iPhone. Apple TV HD
 * is a separate tvOS fallback: Apple documents H.264 1080p60 and HEVC 1080p30
 * (https://support.apple.com/111928). AV1 is filtered before this resolver when
 * VideoToolbox reports no hardware decode support. iOS VLCKit receives the
 * same 4K30 frame-area/throughput envelope as a JellyScope input-safety policy;
 * its codec declaration and provenance remain backend-owned.
 */
internal fun resolveAppleDecodeCeilings(
    deviceModelIdentifier: String,
    platformFamily: ApplePlatformFamily,
    backend: PlayerBackend,
    videoCodecs: List<String>,
): Map<String, VideoCodecResolution> {
    if (backend == PlayerBackend.VlcKit) {
        val iosSafetyEnvelope =
            resolutionCeiling(3_840, 2_160, 30)
                .takeIf { platformFamily == ApplePlatformFamily.Ios }
        return videoCodecs.associateWith { iosSafetyEnvelope ?: VideoCodecResolution() }
    }
    val fallback = avPlayerFallbackCeilings(platformFamily)
    val modelCeilings =
        if (backend == PlayerBackend.AVPlayer) {
            avPlayerModelCeilings(deviceModelIdentifier)
        } else {
            emptyMap()
        }
    return videoCodecs.associateWith { codec -> modelCeilings[codec] ?: fallback[codec] ?: VideoCodecResolution() }
}

private fun avPlayerFallbackCeilings(platformFamily: ApplePlatformFamily): Map<String, VideoCodecResolution> =
    when (platformFamily) {
        ApplePlatformFamily.Ios ->
            mapOf(
                "h264" to resolutionCeiling(3_840, 2_160, 30),
                "hevc" to resolutionCeiling(3_840, 2_160, 30),
                "av1" to resolutionCeiling(3_840, 2_160, 30),
            )
        ApplePlatformFamily.TvOs ->
            mapOf(
                "h264" to resolutionCeiling(1_920, 1_080, 60),
                "hevc" to resolutionCeiling(1_920, 1_080, 30),
            )
    }

/**
 * Apple TV 4K generations use a table entry rather than the Apple TV HD
 * fallback. Apple documents AVC/HEVC 2160p60 for the second generation
 * (https://support.apple.com/en-us/111922) and third generation
 * (https://support.apple.com/en-us/111839). The same documented 4K60 bound is
 * retained for the first generation's A10X family.
 */
private fun avPlayerModelCeilings(deviceModelIdentifier: String): Map<String, VideoCodecResolution> =
    when (deviceModelIdentifier) {
        // iPhone 8 / 8 Plus are the iOS 16 A11 deployment floor cited above.
        // The explicit row proves the model table wins before the iOS fallback;
        // it deliberately claims no more than the documented safe 4K30 floor.
        "iPhone10,1",
        "iPhone10,2",
        "iPhone10,4",
        "iPhone10,5",
        ->
            mapOf(
                "h264" to resolutionCeiling(3_840, 2_160, 30),
                "hevc" to resolutionCeiling(3_840, 2_160, 30),
            )
        "AppleTV6,2", // Apple TV 4K (1st generation)
        "AppleTV11,1", // Apple TV 4K (2nd generation)
        "AppleTV14,1", // Apple TV 4K (3rd generation)
        ->
            mapOf(
                "h264" to resolutionCeiling(3_840, 2_160, 60),
                "hevc" to resolutionCeiling(3_840, 2_160, 60),
            )
        else -> emptyMap()
    }

// VLCKit retains its own codec declaration and evidence. The iOS safety
// envelope above is a product input bound, not an AVPlayer hardware claim.

private fun resolutionCeiling(
    width: Int,
    height: Int,
    framesPerSecond: Int,
): VideoCodecResolution {
    val frameArea = blockPaddedArea(width, height)
    return VideoCodecResolution(
        maxWidth = width,
        maxHeight = height,
        // Keep the same padded-frame accounting used by transcodeResolutionCap:
        // 1920x1080 is billed as 1920x1088 by block-based decoders.
        maxFrameArea = frameArea,
        maxFrameAreaPerSecond = frameArea * framesPerSecond,
    )
}
