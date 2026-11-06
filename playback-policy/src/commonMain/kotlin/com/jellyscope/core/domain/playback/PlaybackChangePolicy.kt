// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Rollback is safe only while both the installed native and reporting owners remain healthy. */
fun canRejectPlaybackChange(
    currentOwner: Boolean,
    reportingIntact: Boolean,
    displacedRecovery: Boolean,
    failedPresentation: Boolean,
    nativeStatus: PlaybackStatus,
): Boolean =
    currentOwner &&
        reportingIntact &&
        !displacedRecovery &&
        !failedPresentation &&
        (
            nativeStatus == PlaybackStatus.Playing ||
                nativeStatus == PlaybackStatus.Paused ||
                nativeStatus == PlaybackStatus.Buffering
        )

/** A held target is presentation only; native progress and output evidence keep their real position. */
fun PlaybackState.withReplacementSeekTarget(targetMs: Long?): PlaybackState =
    when {
        targetMs == null -> this
        status == PlaybackStatus.Failed -> copy(positionMs = targetMs)
        else -> copy(positionMs = targetMs, status = PlaybackStatus.Loading)
    }
