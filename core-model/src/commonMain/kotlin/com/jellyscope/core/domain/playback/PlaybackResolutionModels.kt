// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/**
 * A decoder's usable frame box plus the limits that couple its dimensions.
 *
 * [maxWidth] and [maxHeight] alone overstate what a decoder can do, because
 * hardware expresses a total-blocks limit that ties the two together: the
 * Chromecast/Amlogic AVC and HEVC decoders both declare `size max 4096x4096`
 * while capping `block-count` at 34560, i.e. 4096x2160. [maxFrameArea] carries
 * that coupling as pixels per frame.
 *
 * [maxFrameAreaPerSecond] carries the throughput limit (`blocks-per-second`),
 * which is independent again: the same Amlogic AVC decoder allows 4096x2160 at
 * 30fps but only ~2560x1440 at 60fps, so a frame box that is legal on its own
 * still fails at a high frame rate. Both are null on platforms that do not
 * report them, in which case only the box applies.
 */
data class VideoCodecResolution(
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val maxFrameArea: Long? = null,
    val maxFrameAreaPerSecond: Long? = null,
)

enum class PlaybackResolutionPolicy {
    NoCap,
    VerifiedDeviceCap,
    QualityRung,
    UserSetting,
    VerifiedDeviceAndQualityRung,
    VerifiedDeviceAndUserSetting,
    QualityRungAndUserSetting,
    VerifiedDeviceQualityRungAndUserSetting,
}
