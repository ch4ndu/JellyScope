// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind

enum class WatchNextOrigin {
    ContinueWatching,
    NextUp,
}

data class WatchNextCandidate(
    val item: MediaItem,
    val origin: WatchNextOrigin,
)

/**
 * Selects the ordered, tagged Watch Next candidates from already-fetched rows.
 * Continue-watching candidates are built first so [distinctBy] preserves their
 * precedence when an item occurs in both source lists.
 */
fun selectWatchNextCandidates(
    continueWatching: List<MediaItem>,
    nextUp: List<MediaItem>,
    limit: Int,
): List<WatchNextCandidate> {
    val continueCandidates =
        continueWatching
            .asSequence()
            .filter { item -> item.isPlayableWatchNextCandidate() }
            .filter { item -> !item.played }
            .filter { item -> item.playbackPositionTicks?.let { ticks -> ticks > 0L } == true }
            .map { item -> WatchNextCandidate(item = item, origin = WatchNextOrigin.ContinueWatching) }
            .toList()
    val continueIds = continueCandidates.map { candidate -> candidate.item.id }.toSet()
    val nextCandidates =
        nextUp
            .asSequence()
            .filter { item -> item.isPlayableWatchNextCandidate() }
            .filter { item -> !item.played }
            .filter { item -> item.id !in continueIds }
            .map { item -> WatchNextCandidate(item = item, origin = WatchNextOrigin.NextUp) }
            .toList()

    return (continueCandidates + nextCandidates)
        .distinctBy { candidate -> candidate.item.id }
        .take(limit)
}

private fun MediaItem.isPlayableWatchNextCandidate(): Boolean = kind == MediaKind.Movie || kind == MediaKind.Episode
