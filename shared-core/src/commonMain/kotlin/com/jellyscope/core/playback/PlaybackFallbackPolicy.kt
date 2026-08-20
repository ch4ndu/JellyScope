// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.toPlaybackSessionRecoveryTrigger
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/** Request-policy encoding retained independently of session recovery state. */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
enum class PlaybackFallbackAttempt {
    None,
    DirectPlayDisabled,
    DirectStreamDisabled,
}

/** Builds a fallback policy without changing the backend selected for the request. */
fun PlaybackFallbackAttempt.requestPolicy(backend: PlayerBackend = PlayerBackend.AVPlayer): PlaybackInfoRequestPolicy =
    when (this) {
        PlaybackFallbackAttempt.None -> PlaybackInfoRequestPolicy(backend = backend)
        PlaybackFallbackAttempt.DirectPlayDisabled ->
            PlaybackInfoRequestPolicy(enableDirectPlay = false, backend = backend)
        PlaybackFallbackAttempt.DirectStreamDisabled ->
            PlaybackInfoRequestPolicy(
                enableDirectPlay = false,
                enableDirectStream = false,
                allowAudioStreamCopy = false,
                allowVideoStreamCopy = false,
                backend = backend,
            )
    }

fun PlaybackError?.isPlaybackFallbackEligible(): Boolean = toPlaybackSessionRecoveryTrigger() != null

fun PlaybackStatus.isReadyForStartReport(): Boolean =
    this == PlaybackStatus.Playing ||
        this == PlaybackStatus.Paused ||
        this == PlaybackStatus.Buffering
