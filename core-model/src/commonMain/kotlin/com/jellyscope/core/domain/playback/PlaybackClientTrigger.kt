// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

enum class PlaybackClientTrigger {
    NetworkRetry,
    PlayerFailureFallback,
    BackendFallback,
    AudioActivationFallback,
    SubtitleActivationFallback,

    /**
     * The capability preflight refused to hand the source video to the decoder
     * and forced the re-encode recovery. Without this the overlay showed an
     * app-forced transcode as "App trigger: None" — and the server side is dark
     * too, because AllowVideoStreamCopy=false adds no TranscodeReason.
     */
    DecodeCapabilityCap,

    /** The source exceeds only the user's explicit maximum video resolution. */
    UserResolutionLimit,
}
