// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.Session

interface PlaybackProgressReporter {
    suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    )

    suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    )

    suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    )

    /**
     * Stop settlement with explicit controller evidence. Existing remote reporters remain source
     * compatible through the four-argument method; only the supported Offline router consumes
     * [completed] to set local watched state.
     */
    suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        completed: Boolean,
    ) {
        reportStopped(session, plan, playSessionId, positionMs)
    }
}

enum class PlaybackProgressEvent(
    val wireName: String,
) {
    TimeUpdate("TimeUpdate"),
    Pause("Pause"),
    Unpause("Unpause"),
}
