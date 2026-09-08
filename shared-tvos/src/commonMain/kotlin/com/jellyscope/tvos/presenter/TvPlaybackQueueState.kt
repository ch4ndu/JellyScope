// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.Session

data class TvPlaybackQueueItem(
    val index: Int,
    val media: TvMediaCard,
    val isCurrent: Boolean,
)

data class TvPlaybackQueueState(
    val items: List<TvPlaybackQueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val shuffled: Boolean = false,
    val isPending: Boolean = false,
) {
    val hasPrevious: Boolean
        get() = currentIndex > 0

    val hasNext: Boolean
        get() = currentIndex >= 0 && currentIndex < items.lastIndex
}

internal fun playbackQueueState(
    queue: List<MediaItem>?,
    currentItemId: String,
    shuffled: Boolean,
    session: Session,
    imageUrlBuilder: com.jellyscope.core.domain.model.JellyfinImageUrlBuilder,
): TvPlaybackQueueState {
    val items = queue.orEmpty()
    val currentIndex = items.indexOfFirst { item -> item.id == currentItemId }
    return TvPlaybackQueueState(
        items =
            items.mapIndexed { index, item ->
                TvPlaybackQueueItem(
                    index = index,
                    media = item.toTvMediaCard(session, imageUrlBuilder),
                    isCurrent = index == currentIndex,
                )
            },
        currentIndex = currentIndex,
        shuffled = shuffled,
    )
}
