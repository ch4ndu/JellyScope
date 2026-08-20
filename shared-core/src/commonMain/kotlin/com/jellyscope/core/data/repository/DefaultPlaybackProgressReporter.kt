// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.millisecondsToTicks

class DefaultPlaybackProgressReporter(
    private val jellyfinApi: JellyfinApi,
) : PlaybackProgressReporter {
    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        jellyfinApi.reportPlaybackStart(
            context = session.toRequestContext(),
            itemId = plan.itemId,
            mediaSourceId = plan.mediaSourceId,
            positionTicks = millisecondsToTicks(positionMs),
            playSessionId = playSessionId,
            playMethod = plan.streamMode.playMethod,
        )
    }

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        jellyfinApi.reportPlaybackProgress(
            context = session.toRequestContext(),
            itemId = plan.itemId,
            mediaSourceId = plan.mediaSourceId,
            positionTicks = millisecondsToTicks(positionMs),
            playSessionId = playSessionId,
            playMethod = plan.streamMode.playMethod,
            isPaused = isPaused,
            eventName = eventName.wireName,
        )
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        jellyfinApi.reportPlaybackStopped(
            context = session.toRequestContext(),
            itemId = plan.itemId,
            mediaSourceId = plan.mediaSourceId,
            positionTicks = millisecondsToTicks(positionMs),
            playSessionId = playSessionId,
        )
    }

    private fun Session.toRequestContext(): AuthenticatedRequestContext =
        AuthenticatedRequestContext(
            serverUrl = serverUrl,
            userId = userId,
            accessToken = accessToken,
        )
}

private val StreamMode.playMethod: String
    get() =
        when (this) {
            StreamMode.DirectPlay -> "DirectPlay"
            StreamMode.DirectStream -> "DirectStream"
            StreamMode.Transcode -> "Transcode"
            StreamMode.Offline -> "DirectPlay"
        }
