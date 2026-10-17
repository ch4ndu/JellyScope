// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

sealed class PlaybackPlanningException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    data object NoSupportedStream : PlaybackPlanningException("No supported playback stream is available.")

    /** The decoder cannot safely receive the source video without a transcode. */
    class SourceVideoCopyRejected(
        val detail: SourceVideoCopyRejectionDetail,
    ) : PlaybackPlanningException("Source video exceeds the decoder capability bound.")

    /**
     * Repository-owned transport details are reduced to this project contract
     * before they cross into presentation code.
     */
    class RemoteRequestFailed(
        val isNetworkFailure: Boolean,
        val sourceExceptionType: String,
    ) : PlaybackPlanningException("The remote playback request failed.")

    /** The one allowed forced-transcode recovery did not return a safe stream. */
    class SourceVideoCopyUnsupported(
        val attemptedRequestPolicy: PlaybackInfoRequestPolicy? = null,
        val attemptedMaxStreamingBitrate: Long? = null,
        val attemptedQualityCapOrigin: PlaybackQualityCapOrigin? = null,
        val attemptedQualityPolicy: PlaybackQualityPolicy? = null,
        cause: Throwable? = null,
    ) : PlaybackPlanningException("No decodable transcoded playback stream is available.", cause)
}
