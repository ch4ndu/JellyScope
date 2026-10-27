// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.RELATED_GROUP_DISPLAY_LIMIT
import com.jellyscope.core.domain.playback.PlaybackLaunchContext
import com.jellyscope.ui.screen.player.PlaybackSelection

internal sealed interface DetailChange {
    data object Loading : DetailChange

    data class WatchedStarted(
        val previous: DetailUi,
        val played: Boolean,
    ) : DetailChange

    data class WatchedFinished(
        val previous: DetailUi,
        val succeeded: Boolean,
    ) : DetailChange

    data class AssetsCleared(
        val itemId: String,
        val mediaSourceId: String,
    ) : DetailChange

    data class AssetsUpdated(
        val itemId: String,
        val mediaSourceId: String,
        val assets: List<LocalSubtitleAsset>,
    ) : DetailChange

    data class FavoriteStarted(
        val previous: DetailUi,
        val favorite: Boolean,
    ) : DetailChange

    data class FavoriteFinished(
        val previous: DetailUi,
        val succeeded: Boolean,
    ) : DetailChange

    data class SubtitleDefaultsResolved(
        val detail: DetailUi,
        val mediaSourceId: String,
        val launchContext: PlaybackLaunchContext,
    ) : DetailChange

    data class MediaVersionResolved(
        val detail: DetailUi,
        val mediaSourceId: String,
        val generation: Long,
        val launchContext: PlaybackLaunchContext,
        val rememberedSelection: PlaybackSelection?,
    ) : DetailChange

    data object Failed : DetailChange

    data class Loaded(
        val generation: Long,
        val showLoading: Boolean,
        val detailUi: DetailUi,
    ) : DetailChange

    data class RelatedAdded(
        val groupUi: RelatedGroupUi,
    ) : DetailChange

    data object RelatedCompleted : DetailChange
}

internal fun reduceDetail(
    current: DetailUiState,
    change: DetailChange,
    loadGeneration: Long,
    mediaSourceSelectionGeneration: Long,
    selectedMediaSourceId: String?,
): DetailUiState =
    when (change) {
        DetailChange.Loading -> DetailUiState.Loading
        is DetailChange.WatchedStarted ->
            with(change) {
                if (current is DetailUiState.Content && current.detail.itemId == previous.itemId) {
                    current.copy(detail = current.detail.withWatched(played).copy(watchedToggleInFlight = true))
                } else {
                    current
                }
            }
        is DetailChange.WatchedFinished ->
            with(change) {
                if (current is DetailUiState.Content && current.detail.itemId == previous.itemId) {
                    if (succeeded) {
                        current.copy(detail = current.detail.copy(watchedToggleInFlight = false))
                    } else {
                        current.copy(detail = current.detail.withWatchedRollbackFrom(previous))
                    }
                } else {
                    current
                }
            }
        is DetailChange.AssetsCleared ->
            with(change) {
                if (current is DetailUiState.Content && current.detail.itemId == itemId) {
                    current.copy(detail = current.detail.withLocalSubtitleAssets(mediaSourceId, emptyList()))
                } else {
                    current
                }
            }
        is DetailChange.AssetsUpdated ->
            with(change) {
                if (current is DetailUiState.Content &&
                    current.detail.itemId == itemId &&
                    current.detail.selectedMediaSourceId == mediaSourceId
                ) {
                    current.copy(detail = current.detail.withLocalSubtitleAssets(mediaSourceId, assets))
                } else {
                    current
                }
            }
        is DetailChange.FavoriteStarted ->
            with(change) {
                if (current is DetailUiState.Content && current.detail.itemId == previous.itemId) {
                    current.copy(detail = current.detail.copy(isFavorite = favorite, favoriteToggleInFlight = true))
                } else {
                    current
                }
            }
        is DetailChange.FavoriteFinished ->
            with(change) {
                if (current is DetailUiState.Content && current.detail.itemId == previous.itemId) {
                    if (succeeded) {
                        current.copy(detail = current.detail.copy(favoriteToggleInFlight = false))
                    } else {
                        current.copy(
                            detail =
                                current.detail.copy(
                                    isFavorite = previous.isFavorite,
                                    favoriteToggleInFlight = false,
                                ),
                        )
                    }
                } else {
                    current
                }
            }
        is DetailChange.SubtitleDefaultsResolved ->
            with(change) {
// Read the defaults off the state being committed, not the snapshot
                // above: a local-asset emission may have landed while the stores
                // were being read, and that list must survive this update.
                if (current is DetailUiState.Content &&
                    current.detail.itemId == detail.itemId &&
                    current.detail.trackSelection.mediaSourceId == mediaSourceId
                ) {
                    val updatedTrackSelection =
                        current.detail.trackSelection.reprojectSubtitleDefaults(
                            playbackPreferences = launchContext.playbackPreferences,
                            storedSubtitleSelection = launchContext.subtitleSelection,
                        )
                    val selectedVersion =
                        current.detail.versions.firstOrNull { version -> version.id == mediaSourceId }
                    current.copy(
                        detail =
                            if (selectedVersion != null) {
                                current.detail.withSelectedMediaVersion(
                                    selectedVersion.copy(trackSelection = updatedTrackSelection),
                                )
                            } else {
                                current.detail.copy(trackSelection = updatedTrackSelection)
                            },
                    )
                } else {
                    current
                }
            }
        is DetailChange.MediaVersionResolved ->
            with(change) {
                val currentDetail = (current as? DetailUiState.Content)?.detail ?: return@with current
                val currentVersion =
                    currentDetail.versions.firstOrNull { version -> version.id == mediaSourceId }
                        ?: return@with current
                if (generation != mediaSourceSelectionGeneration ||
                    currentDetail.itemId != detail.itemId ||
                    selectedMediaSourceId != mediaSourceId
                ) {
                    current
                } else {
                    val resolvedVersion =
                        currentVersion.resolveLaunchDefaults(
                            playbackPreferences = launchContext.playbackPreferences,
                            rememberedSelection = rememberedSelection,
                            storedSubtitleSelection = launchContext.subtitleSelection,
                        )
                    current.copy(detail = currentDetail.withSelectedMediaVersion(resolvedVersion))
                }
            }
        DetailChange.Failed ->
            with(change) {
                DetailUiState.Error()
            }
        is DetailChange.Loaded ->
            with(change) {
                if (generation != loadGeneration) {
                    current
                } else if (showLoading) {
                    DetailUiState.Content(detail = detailUi)
                } else if (current is DetailUiState.Content && current.detail.itemId == detailUi.itemId) {
                    // Related can still be streaming while a retained Detail route
                    // silently refreshes. Merge at commit time so groups emitted
                    // during the refresh are never overwritten or marked complete.
                    DetailUiState.Content(
                        detail =
                            detailUi.copy(
                                relatedGroups = current.detail.relatedGroups,
                                relatedLoading = current.detail.relatedLoading,
                            ),
                    )
                } else {
                    current
                }
            }
        is DetailChange.RelatedAdded ->
            with(change) {
                if (current is DetailUiState.Content) {
                    val relatedGroups = current.detail.relatedGroups + groupUi
                    current.copy(
                        detail =
                            current.detail.copy(
                                relatedGroups = relatedGroups,
                                relatedLoading =
                                    if (relatedGroups.size >= RELATED_GROUP_DISPLAY_LIMIT) {
                                        false
                                    } else {
                                        current.detail.relatedLoading
                                    },
                            ),
                    )
                } else {
                    current
                }
            }
        DetailChange.RelatedCompleted ->
            with(change) {
                if (current is DetailUiState.Content) {
                    current.copy(detail = current.detail.copy(relatedLoading = false))
                } else {
                    current
                }
            }
    }
