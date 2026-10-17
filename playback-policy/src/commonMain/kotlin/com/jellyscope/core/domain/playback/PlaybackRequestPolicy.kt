// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

data class PlaybackInfoRequestPolicy(
    val enableDirectPlay: Boolean = true,
    val enableDirectStream: Boolean = true,
    val enableTranscoding: Boolean = true,
    val allowAudioStreamCopy: Boolean = true,
    val allowVideoStreamCopy: Boolean = true,
    val forceEncodeSubtitle: ForceEncodeSubtitle? = null,
    val backend: PlayerBackend = PlayerBackend.AVPlayer,
    val clientTrigger: PlaybackClientTrigger? = null,
    /** Derived centrally from a user-selected quality rung; never from automatic caps. */
    val qualityResolutionCap: VideoCodecResolution? = null,
    val qualityCapOrigin: PlaybackQualityCapOrigin? = null,
    /** Snapshotted from the global player-device setting at the start of planning. */
    val userVideoResolutionCap: VideoCodecResolution? = null,
    /** Distinguishes a snapshotted Unlimited value from an unset policy field. */
    val userVideoResolutionCapIsResolved: Boolean = false,
    /** Sent only by the forced-transcode recovery and not trusted until server-probed. */
    val maxFramerate: Int? = null,
    /** Internal request/profile bitrate contract; never a product quality choice. */
    val bitrateConstraint: PlaybackBitrateConstraint = PlaybackBitrateConstraint.NoClientLimit,
    /** Explicit authorization for a planner recovery attempt. */
    val recoveryIntent: PlaybackRecoveryIntent = PlaybackRecoveryIntent.Initial,
    /** Process-local diagnostic correlation only; never sent to Jellyfin. */
    val diagnosticSessionSequence: Long? = null,
)

data class ForceEncodeSubtitle(
    val streamIndex: Int,
    val normalizedFormat: String,
)
