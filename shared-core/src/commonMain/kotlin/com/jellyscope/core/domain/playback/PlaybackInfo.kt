// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

data class PlaybackInfo(
    val playSessionId: String?,
    val mediaSources: List<PlaybackMediaSourceInfo>,
)

data class PlaybackMediaSourceInfo(
    val id: String?,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val supportsTranscoding: Boolean,
    val transcodingUrl: String?,
    val container: String?,
    val transcodingContainer: String? = null,
    val transcodingSubProtocol: String? = null,
    val defaultAudioStreamIndex: Int? = null,
    val defaultSubtitleStreamIndex: Int? = null,
    val bitrate: Long?,
    val transcodeReasons: List<String> = emptyList(),
    val mediaStreams: List<PlaybackMediaStream> = emptyList(),
)
