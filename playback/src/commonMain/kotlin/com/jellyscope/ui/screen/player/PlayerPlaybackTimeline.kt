// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.playback.PlaybackContentTimeline
import com.jellyscope.core.domain.playback.PlaybackContentTimelineSource
import com.jellyscope.core.domain.playback.PlaybackPlan

internal data class ActivePlaybackTimelineFacts(
    val generation: Long,
    val itemId: String,
    val kind: MediaKind,
    val isLive: Boolean?,
    val itemRuntimeMs: Long?,
    val sourcesById: Map<String, ActivePlaybackTimelineSourceFacts>,
)

internal data class ActivePlaybackTimelineSourceFacts(
    val runtimeMs: Long?,
    val isInfiniteStream: Boolean?,
)

internal fun MediaItemDetail.selectedVersion(requestedMediaSourceId: String?): MediaVersion? =
    requestedMediaSourceId
        ?.takeIf { id -> id.isNotBlank() }
        ?.let { id -> versions.firstOrNull { version -> version.id == id } }
        ?: versions.firstOrNull { version -> version.id.isNotBlank() }

internal fun MediaItemDetail.toActivePlaybackTimelineFacts(generation: Long): ActivePlaybackTimelineFacts =
    ActivePlaybackTimelineFacts(
        generation = generation,
        itemId = item.id,
        kind = item.kind,
        isLive = item.isLive,
        itemRuntimeMs = item.runtime?.inWholeMilliseconds,
        sourcesById =
            versions
                .filter { version -> version.id.isNotBlank() }
                .associate { version ->
                    version.id to
                        ActivePlaybackTimelineSourceFacts(
                            runtimeMs = version.runtime?.inWholeMilliseconds,
                            isInfiniteStream = version.isInfiniteStream,
                        )
                },
    )

internal fun resolvePlaybackContentTimeline(
    playbackPlan: PlaybackPlan,
    requestedMediaSourceId: String,
    facts: ActivePlaybackTimelineFacts?,
): PlaybackContentTimeline {
    val timelineFacts = facts ?: return PlaybackContentTimeline.UnknownOrUnbounded
    if (timelineFacts.kind != MediaKind.Movie && timelineFacts.kind != MediaKind.Episode) {
        return PlaybackContentTimeline.UnknownOrUnbounded
    }
    if (timelineFacts.isLive == true) return PlaybackContentTimeline.UnknownOrUnbounded
    val sourceFacts =
        timelineFacts.sourcesById[playbackPlan.mediaSourceId]
            ?: return PlaybackContentTimeline.UnknownOrUnbounded
    if (sourceFacts.isInfiniteStream == true) return PlaybackContentTimeline.UnknownOrUnbounded
    sourceFacts.runtimeMs?.takeIf { durationMs -> durationMs > 0L }?.let { durationMs ->
        return PlaybackContentTimeline.BoundedVod(
            durationMs = durationMs,
            source = PlaybackContentTimelineSource.SelectedMediaSource,
        )
    }
    if (playbackPlan.mediaSourceId != requestedMediaSourceId) {
        return PlaybackContentTimeline.UnknownOrUnbounded
    }
    return timelineFacts.itemRuntimeMs
        ?.takeIf { durationMs -> durationMs > 0L }
        ?.let { durationMs ->
            PlaybackContentTimeline.BoundedVod(
                durationMs = durationMs,
                source = PlaybackContentTimelineSource.ItemFallback,
            )
        } ?: PlaybackContentTimeline.UnknownOrUnbounded
}
