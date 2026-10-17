// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetItemsByIdsUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class PlayerQueueProjection(
    private val getItemDetailUseCase: GetItemDetailUseCase,
    private val getItemsByIdsUseCase: GetItemsByIdsUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val serverUrl: String,
    private val workDispatcher: CoroutineDispatcher,
) {
    suspend fun resolveQueueItems(queueIds: List<String>): Map<String, QueueItemUi> =
        withContext(workDispatcher) {
            val itemsById =
                queueIds
                    .chunked(QUEUE_METADATA_BATCH_SIZE)
                    .flatMap { batch ->
                        getItemsByIdsUseCase(batch).getOrDefault(emptyList())
                    }.associateBy { item -> item.id }
                    .toMutableMap()

            queueIds
                .filterNot { id -> id in itemsById }
                .forEach { id ->
                    getItemDetailUseCase(id, includePlaybackFields = false)
                        .getOrNull()
                        ?.item
                        ?.let { item -> itemsById[id] = item }
                }

            itemsById.mapValues { entry -> entry.value.toQueueItemUi() }
        }

    fun queueItems(
        queueIds: List<String>,
        itemsById: Map<String, QueueItemUi>,
    ): List<QueueItemUi> = queueIds.map { id -> itemsById[id] ?: unresolvedQueueItem(id) }

    fun queueItemsForMediaItems(
        queueIds: List<String>,
        itemsById: Map<String, MediaItem>,
    ): List<QueueItemUi> = queueIds.map { id -> itemsById[id]?.toQueueItemUi() ?: unresolvedQueueItem(id) }

    fun upNextFor(
        playbackState: PlaybackState,
        playlist: PlaylistUi?,
        mediaSegments: List<MediaSegment>,
    ): UpNextInfo? {
        if (!playbackState.isInUpNextWindow(mediaSegments)) {
            return null
        }

        val currentPlaylist = playlist ?: return null
        val nextIndex = currentPlaylist.currentIndex + 1
        val nextItem = currentPlaylist.items.getOrNull(nextIndex) ?: return null
        return UpNextInfo(
            itemId = nextItem.id,
            title = nextItem.title,
            imageUrl = nextItem.imageUrl,
            index = nextIndex,
            seasonNumber = nextItem.seasonNumber,
            episodeNumber = nextItem.episodeNumber,
        )
    }

    fun trickplayTileUrls(
        itemId: String,
        trickplay: TrickplayInfo?,
    ): List<String> =
        if (trickplay == null) {
            emptyList()
        } else {
            List(trickplay.tileCount) { index ->
                imageUrlBuilder.trickplayTileUrl(
                    serverUrl = serverUrl,
                    itemId = itemId,
                    mediaSourceId = trickplay.mediaSourceId,
                    width = trickplay.width,
                    index = index,
                )
            }
        }

    private fun MediaItem.toQueueItemUi(): QueueItemUi =
        QueueItemUi(
            id = id,
            title = name,
            imageUrl =
                imageRefs.primaryTag?.let { tag ->
                    imageUrlBuilder.build(
                        serverUrl = serverUrl,
                        itemId = id,
                        type = JellyfinImageType.Primary,
                        tag = tag,
                        maxWidth = 300,
                    )
                },
            seasonNumber = parentIndexNumber,
            episodeNumber = indexNumber,
        )

    private fun unresolvedQueueItem(id: String): QueueItemUi =
        QueueItemUi(
            id = id,
            title = "",
            imageUrl = null,
        )

    private fun PlaybackState.isInUpNextWindow(mediaSegments: List<MediaSegment>): Boolean {
        if (status == PlaybackStatus.Completed) {
            return true
        }
        if (status != PlaybackStatus.Playing) {
            return false
        }

        val positionMs = positionMs.coerceAtLeast(0L)
        val durationMs = durationMs?.coerceAtLeast(0L)
        val outroSegment = mediaSegments.firstOrNull { segment -> segment.type == MediaSegmentType.Outro }
        if (outroSegment != null) {
            val beforeDurationEnd = durationMs == null || positionMs < durationMs
            return positionMs >= outroSegment.startMs && beforeDurationEnd
        }

        val thresholdStartMs =
            durationMs
                ?.minus(UP_NEXT_THRESHOLD_MS)
                ?.coerceAtLeast(0L)
                ?: return false
        return positionMs >= thresholdStartMs && positionMs < durationMs
    }
}
