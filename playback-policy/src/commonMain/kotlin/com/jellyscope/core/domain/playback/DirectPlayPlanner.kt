// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.Session

class DirectPlayPlanner {
    fun plan(
        session: Session,
        itemId: String,
        mediaSourceId: String,
        startPositionTicks: Long,
        detailMediaStreams: List<PlaybackMediaStream> = emptyList(),
    ): PlaybackPlan =
        PlaybackPlan(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            startPositionMs = ticksToMilliseconds(startPositionTicks),
            streamMode = StreamMode.DirectPlay,
            streamUrl =
                directPlayStreamUrl(
                    serverUrl = session.serverUrl,
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    deviceId = session.deviceId,
                ),
            progressReportingPolicy =
                ProgressReportingPolicy(
                    reportIntervalMs = DEFAULT_PLAYBACK_REPORT_INTERVAL_MS,
                ),
            videoPresentation =
                detailMediaStreams
                    .firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
                    ?.toPlannedVideoPresentation(),
        )

    private fun PlaybackMediaStream.toPlannedVideoPresentation(): PlannedVideoPresentation =
        PlannedVideoPresentation(
            width = width,
            height = height,
            frameRate = realFrameRate,
            videoRangeType = videoRangeType,
            bitDepth = bitDepth,
        )
}

const val DEFAULT_PLAYBACK_REPORT_INTERVAL_MS = 10_000L
