// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.PlaybackTimeline
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.SendPlaybackTimelineResult
import com.jellyscope.core.util.LogBufferStore

class SendPlaybackTimelineAction(
    private val logBufferStore: LogBufferStore,
    private val mediaRepository: MediaRepository,
) {
    /**
     * Explicitly uploads an already-generated timeline through the existing client-log document
     * route. Unlike regular diagnostics upload, this never acknowledges or clears retained history.
     */
    suspend operator fun invoke(timeline: PlaybackTimeline): SendPlaybackTimelineResult {
        // Final authority check: collection must still be on and in the same boundary.
        if (!logBufferStore.isCollectionEnabled()) return SendPlaybackTimelineResult.CollectionDisabled
        if (logBufferStore.captureBoundary() != timeline.collectionBoundary) return SendPlaybackTimelineResult.Expired
        return when (val result = mediaRepository.uploadClientLogs(timeline.json)) {
            is SendClientLogsResult.Success -> SendPlaybackTimelineResult.Success(result.filename)
            SendClientLogsResult.UploadDisallowed -> SendPlaybackTimelineResult.UploadDisallowed
            SendClientLogsResult.Failure -> SendPlaybackTimelineResult.Failure
        }
    }
}
