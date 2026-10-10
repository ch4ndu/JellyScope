// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.jellyscope.core.domain.model.PlaybackTimeline

/** Terminal outcome of one native save step; closed so feedback and diagnostics stay identity-free. */
enum class PlaybackTimelineSaveResult {
    Saved,
    Cancelled,

    /** No native destination provider or picker could be opened. */
    Unavailable,

    /**
     * A destination arrived without a matching live payload (activity or process restoration,
     * cancellation, or collection turned off). No diagnostic bytes were written, but the platform
     * may already have created an empty destination file that this app cannot prove it owns.
     */
    Interrupted,

    /** Collection was turned off or reset before the bytes were handed to the platform. */
    Expired,

    /**
     * The picker could not be opened, staging failed, or the write failed. A write that failed
     * partway may leave an incomplete file at the chosen destination; it is never deleted.
     */
    Failure,
}

/** Never names a live operation; the ViewModel never allocates this id. */
internal const val NO_PLAYBACK_TIMELINE_OPERATION = 0L

/**
 * Owner of the single pending save. The bridge holds no payload of its own: it asks for the
 * still-deliverable timeline immediately before each native handoff and reports one result.
 */
interface PlaybackTimelineSaveHost {
    /** The timeline for [operationId] only while that operation is current and collection still allows it. */
    fun deliverablePlaybackTimeline(operationId: Long): PlaybackTimeline?

    /** Ends [operationId] if it is still current; a result for any other operation never ends the current one. */
    fun onPlaybackTimelineSaveResult(
        operationId: Long,
        result: PlaybackTimelineSaveResult,
    )

    /** Invalidates the current timeline operation when its screen leaves composition. */
    fun releasePlaybackTimelineOperation()
}

@Stable
interface PlaybackTimelineExporter {
    /**
     * Opens the platform destination picker for [operationId]. Every request, including a stale or
     * busy one, reports exactly one result; the host ignores results it no longer owns.
     */
    fun launch(operationId: Long)
}

/**
 * Small one-shot native save bridge: Android Storage Access Framework, the iOS export document
 * picker, or the JDK save dialog. Platform resources live only while this composable is composed.
 */
@Composable
expect fun rememberPlaybackTimelineExporter(host: PlaybackTimelineSaveHost): PlaybackTimelineExporter
