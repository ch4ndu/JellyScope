// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

/** Fixed generic name for saved timelines; it never contains media, account, or device data. */
const val PLAYBACK_TIMELINE_FILENAME = "jellyscope-playback-timeline.json"

/**
 * An explicitly prepared Chrome trace-event JSON document. The text is immutable; delivery code
 * converts it to bytes only at the native boundary. [collectionBoundary] is local bookkeeping that
 * lets delivery reject a payload prepared before collection was disabled, purged, or cleared.
 */
@ConsistentCopyVisibility
data class PlaybackTimeline internal constructor(
    val json: String,
    val eventCount: Int,
    /** False when dropped, legacy, malformed, truncated, or omitted history limits coverage. */
    val historyComplete: Boolean,
    internal val collectionBoundary: Long,
) {
    val filename: String
        get() = PLAYBACK_TIMELINE_FILENAME
}

sealed interface PreparePlaybackTimelineResult {
    data class Ready(
        val timeline: PlaybackTimeline,
    ) : PreparePlaybackTimelineResult

    /** Collection is off, or it changed boundary while the snapshot was being taken. */
    data object CollectionDisabled : PreparePlaybackTimelineResult

    /** No timed playback event was collected; legacy-only history also lands here. */
    data object NoTimedEvents : PreparePlaybackTimelineResult

    data object Failure : PreparePlaybackTimelineResult
}

sealed interface SendPlaybackTimelineResult {
    data class Success(
        val filename: String,
    ) : SendPlaybackTimelineResult

    data object CollectionDisabled : SendPlaybackTimelineResult

    /** The timeline belongs to an earlier collection boundary; prepare a new one. */
    data object Expired : SendPlaybackTimelineResult

    data object UploadDisallowed : SendPlaybackTimelineResult

    data object Failure : SendPlaybackTimelineResult
}
