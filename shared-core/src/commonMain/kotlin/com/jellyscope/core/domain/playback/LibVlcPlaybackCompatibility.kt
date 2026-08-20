// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

internal data class PlaybackInfoAttempt(
    val playbackInfo: PlaybackInfo,
    val maxStreamingBitrate: Long?,
    val qualityCapOrigin: PlaybackQualityCapOrigin?,
    val requestPolicy: PlaybackInfoRequestPolicy,
)

internal fun canonicalVideoCodec(value: String?): String? =
    value
        ?.trim()
        ?.lowercase()
        ?.let { codec ->
            when (codec) {
                "av01" -> "av1"
                "h265", "x265" -> "hevc"
                "h264", "x264" -> "h264"
                else -> codec
            }
        }?.takeIf { codec -> codec.isNotBlank() }

internal fun isVideoRangeSupported(
    value: String?,
    capabilities: VideoRangeCapabilities,
): Boolean {
    val range = value?.filter(Char::isLetterOrDigit)?.lowercase() ?: return true
    return when {
        range == "sdr" -> true
        range == "hdr10" -> capabilities.supportsHdr10
        range == "hdr10plus" -> capabilities.supportsHdr10Plus || capabilities.supportsHdr10
        range == "hlg" -> capabilities.supportsHlg
        range == "dovi" -> capabilities.supportsDolbyVision
        range == "doviwithhdr10" -> capabilities.supportsDolbyVision || capabilities.supportsHdr10
        range == "doviwithhdr10plus" -> capabilities.supportsDolbyVision || capabilities.supportsHdr10Plus || capabilities.supportsHdr10
        range == "doviwithhlg" -> capabilities.supportsDolbyVision || capabilities.supportsHlg
        range == "doviwithsdr" -> true
        range == "doviwithel" -> capabilities.supportsDolbyVisionWithEL
        range == "doviwithelhdr10plus" -> capabilities.supportsDolbyVisionWithEL
        else -> true
    }
}
