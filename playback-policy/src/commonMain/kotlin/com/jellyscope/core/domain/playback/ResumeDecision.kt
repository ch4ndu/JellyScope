// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

sealed interface ResumeDecision {
    data object Start : ResumeDecision

    data class Resume(
        val position: Duration,
    ) : ResumeDecision

    data object StartOver : ResumeDecision
}

private val nearCompleteThreshold = 30.seconds

fun resumeDecision(
    playbackPositionTicks: Long?,
    runtime: Duration?,
    played: Boolean,
): ResumeDecision {
    if (played) {
        return ResumeDecision.StartOver
    }

    val position = playbackPositionTicks.toPlaybackDuration()
    if (position <= Duration.ZERO) {
        return ResumeDecision.Start
    }

    if (runtime != null && runtime > Duration.ZERO && runtime - position <= nearCompleteThreshold) {
        return ResumeDecision.StartOver
    }

    return ResumeDecision.Resume(position)
}

fun formattedResumePosition(position: Duration): String {
    val hours = position.inWholeHours
    val minutes = (position - hours.hours).inWholeMinutes

    return if (hours > 0) {
        "$hours h $minutes min"
    } else {
        "$minutes min"
    }
}
