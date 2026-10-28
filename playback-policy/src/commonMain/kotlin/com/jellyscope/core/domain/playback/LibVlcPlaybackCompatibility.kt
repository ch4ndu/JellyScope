// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
data class PlaybackInfoAttempt(
    val playbackInfo: PlaybackInfo,
    val maxStreamingBitrate: Long?,
    val qualityCapOrigin: PlaybackQualityCapOrigin?,
    val requestPolicy: PlaybackInfoRequestPolicy,
)

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun canonicalVideoCodec(value: String?): String? =
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
