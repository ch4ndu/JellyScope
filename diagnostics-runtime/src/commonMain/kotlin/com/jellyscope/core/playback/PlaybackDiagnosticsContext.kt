// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlayerBackend

data class DiagnosticsSourceDescriptor(
    val videoCodec: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val bitRate: Long?,
    val bitDepth: Int?,
    val videoRangeType: String?,
    val container: String?,
    val audioCodec: String?,
    val channelLayout: String?,
)

data class PlaybackDiagnosticsSnapshot(
    val backend: PlayerBackend,
    val capabilities: DeviceDecodingCapabilities?,
    val source: DiagnosticsSourceDescriptor?,
)

fun List<PlaybackMediaStream>.toDiagnosticsSourceDescriptor(
    container: String?,
    selectedAudioStreamIndex: Int?,
): DiagnosticsSourceDescriptor {
    val video = firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
    val audio =
        firstOrNull { stream ->
            stream.type.equals("Audio", ignoreCase = true) &&
                selectedAudioStreamIndex != null &&
                stream.index == selectedAudioStreamIndex
        } ?: firstOrNull { stream -> stream.type.equals("Audio", ignoreCase = true) }
    return DiagnosticsSourceDescriptor(
        videoCodec = video?.codec,
        width = video?.width,
        height = video?.height,
        frameRate = video?.realFrameRate,
        bitRate = video?.bitRate,
        bitDepth = video?.bitDepth,
        videoRangeType = video?.videoRangeType,
        container = container,
        audioCodec = audio?.codec,
        channelLayout = audio?.channelLayout,
    )
}
