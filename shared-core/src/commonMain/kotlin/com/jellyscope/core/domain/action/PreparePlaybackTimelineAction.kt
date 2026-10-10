// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.domain.model.PlaybackTimeline
import com.jellyscope.core.domain.model.PreparePlaybackTimelineResult
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.PlaybackDiagnosticTimeline
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PreparePlaybackTimelineAction(
    private val logBufferStore: LogBufferStore,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Encodes the currently admitted history after an explicit user request. Encoding runs on the
     * worker dispatcher and never uploads, acknowledges, or clears retained history.
     */
    suspend operator fun invoke(): PreparePlaybackTimelineResult {
        if (!logBufferStore.isCollectionEnabled()) return PreparePlaybackTimelineResult.CollectionDisabled
        val boundary = logBufferStore.captureBoundary()
        val snapshot = logBufferStore.snapshot()
        if (!isCurrent(boundary)) return PreparePlaybackTimelineResult.CollectionDisabled
        val encoded =
            try {
                withContext(workerDispatcher) { PlaybackDiagnosticTimeline.encode(snapshot) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (failure: Throwable) {
                // Fixed stage/event and sanitized type only; never the snapshot or exception message.
                playbackTimelineLogger.w {
                    formatSafeFailureDiagnostic(stage = "playback-timeline", event = "encode-failed", throwable = failure)
                }
                return PreparePlaybackTimelineResult.Failure
            } ?: return PreparePlaybackTimelineResult.NoTimedEvents
        return PreparePlaybackTimelineResult.Ready(
            PlaybackTimeline(
                json = encoded.json,
                eventCount = encoded.eventCount,
                historyComplete = encoded.historyComplete,
                collectionBoundary = boundary,
            ),
        )
    }

    /**
     * Final authority check immediately before bytes are handed to a native exporter. A timeline
     * prepared before collection was disabled, purged, or cleared is never deliverable again.
     */
    fun isDeliverable(timeline: PlaybackTimeline): Boolean = isCurrent(timeline.collectionBoundary)

    private fun isCurrent(boundary: Long): Boolean = logBufferStore.isCollectionEnabled() && logBufferStore.captureBoundary() == boundary
}

private val playbackTimelineLogger = diagnosticLogger(DiagnosticTag.PlaybackTimeline)
