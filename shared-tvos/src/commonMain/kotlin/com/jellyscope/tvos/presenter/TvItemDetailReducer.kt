// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.MediaItem

internal sealed interface TvItemDetailChange {
    data class LoadStarted(
        val refreshing: Boolean,
    ) : TvItemDetailChange

    data class LoadFailed(
        val error: TvErrorKind,
    ) : TvItemDetailChange

    data class VersionSelected(
        val mediaSourceId: String,
    ) : TvItemDetailChange

    data class AudioSelected(
        val streamIndex: Int,
    ) : TvItemDetailChange

    data class SubtitleSelected(
        val streamIndex: Int?,
    ) : TvItemDetailChange

    data object LocalSelectionFailed : TvItemDetailChange

    data object LocalSelectionStarted : TvItemDetailChange

    data class LocalSelectionSaved(
        val assetId: String?,
    ) : TvItemDetailChange

    data class PlayedChanged(
        val targetItemId: String,
        val target: Boolean,
        val item: MediaItem?,
    ) : TvItemDetailChange

    data class PlayedRolledBack(
        val targetItemId: String,
        val played: Boolean,
        val item: MediaItem?,
        val error: TvErrorKind,
    ) : TvItemDetailChange

    data class FavoriteChanged(
        val targetItemId: String,
        val target: Boolean,
    ) : TvItemDetailChange

    data class FavoriteRolledBack(
        val targetItemId: String,
        val favorite: Boolean,
        val error: TvErrorKind,
    ) : TvItemDetailChange

    data object OptionsStarted : TvItemDetailChange

    data class ActionFailed(
        val error: TvErrorKind,
    ) : TvItemDetailChange

    data class Projected(
        val projection: TvDetailProjection,
        val content: TvItemDetailContent,
    ) : TvItemDetailChange

    data class RelatedLoaded(
        val groups: List<TvRelatedGroup>,
    ) : TvItemDetailChange

    data object RelatedFinished : TvItemDetailChange

    data object SeriesCleared : TvItemDetailChange

    data class Series(
        val change: TvSeriesChange,
    ) : TvItemDetailChange
}

internal fun reduceTvItemDetail(
    current: TvItemDetailState,
    change: TvItemDetailChange,
): TvItemDetailState =
    when (change) {
        is TvItemDetailChange.LoadStarted ->
            with(change) {
                current.copy(
                    isLoading = !refreshing,
                    isRefreshing = refreshing,
                    relatedLoading = true,
                    error = null,
                    actionError = null,
                )
            }
        is TvItemDetailChange.LoadFailed ->
            with(change) {
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    relatedLoading = false,
                    error = error,
                )
            }
        is TvItemDetailChange.VersionSelected ->
            with(change) {
                current.copy(
                    content = current.content?.copy(isPlayable = false),
                    versions =
                        current.versions.map { version ->
                            version.copy(selected = version.id == mediaSourceId)
                        },
                    selectedMediaSourceId = mediaSourceId,
                    audioTracks = emptyList(),
                    subtitleTracks = emptyList(),
                    selectedSubtitleAssetId = null,
                    isSubtitleSelectionPending = false,
                    subtitleSelectionFailed = false,
                    mediaInfo = emptyList(),
                    actionError = null,
                )
            }
        is TvItemDetailChange.AudioSelected ->
            with(change) {
                current.copy(
                    audioTracks = current.audioTracks.map { track -> track.copy(selected = track.streamIndex == streamIndex) },
                    actionError = null,
                )
            }
        is TvItemDetailChange.SubtitleSelected ->
            with(change) {
                current.copy(
                    subtitleMode = if (streamIndex == null) TvPlaybackSubtitleMode.Off else TvPlaybackSubtitleMode.Track,
                    selectedSubtitleStreamIndex = streamIndex,
                    selectedSubtitleAssetId = null,
                    subtitleTracks = current.subtitleTracks.map { track -> track.copy(selected = track.streamIndex == streamIndex) },
                    isSubtitleSelectionPending = false,
                    subtitleSelectionFailed = false,
                    actionError = null,
                )
            }
        TvItemDetailChange.LocalSelectionFailed ->
            run {
                current.copy(
                    isSubtitleSelectionPending = false,
                    subtitleSelectionFailed = true,
                    actionError = TvErrorKind.Unknown,
                )
            }
        TvItemDetailChange.LocalSelectionStarted ->
            run {
                current.copy(
                    isSubtitleSelectionPending = true,
                    subtitleSelectionFailed = false,
                    actionError = null,
                )
            }
        is TvItemDetailChange.LocalSelectionSaved ->
            with(change) {
                current.copy(
                    subtitleMode = if (assetId == null) TvPlaybackSubtitleMode.Off else TvPlaybackSubtitleMode.LocalAsset,
                    selectedSubtitleStreamIndex = null,
                    selectedSubtitleAssetId = assetId,
                    subtitleTracks = current.subtitleTracks.map { track -> track.copy(selected = false) },
                    isSubtitleSelectionPending = false,
                    subtitleSelectionFailed = false,
                    actionError = null,
                )
            }
        is TvItemDetailChange.PlayedChanged ->
            with(change) {
                current.copy(
                    content =
                        current.content
                            ?.takeIf { it.id == targetItemId }
                            ?.withPlayedStatus(target, item)
                            ?: current.content,
                    actionError = null,
                )
            }
        is TvItemDetailChange.PlayedRolledBack ->
            with(change) {
                current.copy(
                    content =
                        current.content
                            ?.takeIf { it.id == targetItemId }
                            ?.withPlayedStatus(played, item)
                            ?: current.content,
                    actionError = error,
                )
            }
        is TvItemDetailChange.FavoriteChanged ->
            with(change) {
                current.copy(
                    content = current.content?.takeIf { it.id == targetItemId }?.copy(isFavorite = target) ?: current.content,
                    actionError = null,
                )
            }
        is TvItemDetailChange.FavoriteRolledBack ->
            with(change) {
                current.copy(
                    content =
                        current.content?.takeIf { it.id == targetItemId }?.copy(isFavorite = favorite)
                            ?: current.content,
                    actionError = error,
                )
            }
        TvItemDetailChange.OptionsStarted ->
            run {
                current.copy(
                    content = current.content?.copy(isPlayable = false),
                    audioTracks = emptyList(),
                    subtitleTracks = emptyList(),
                    mediaInfo = emptyList(),
                    actionError = null,
                )
            }
        is TvItemDetailChange.ActionFailed ->
            with(change) {
                current.copy(actionError = error)
            }
        is TvItemDetailChange.Projected ->
            with(change) {
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    content = content,
                    versions = projection.versions,
                    selectedMediaSourceId = projection.selectedMediaSourceId,
                    audioTracks = projection.audioTracks,
                    subtitleTracks = projection.subtitleTracks,
                    subtitleMode = projection.subtitleMode,
                    selectedSubtitleStreamIndex = projection.selectedSubtitleStreamIndex,
                    selectedSubtitleAssetId = projection.selectedSubtitleAssetId,
                    mediaInfo = projection.mediaInfo,
                    cast = projection.cast,
                    crew = projection.crew,
                    error = null,
                    actionError = current.actionError,
                )
            }
        is TvItemDetailChange.RelatedLoaded ->
            with(change) {
                current.copy(relatedGroups = groups)
            }
        TvItemDetailChange.RelatedFinished ->
            run {
                current.copy(relatedLoading = false)
            }
        TvItemDetailChange.SeriesCleared ->
            run {
                current.copy(
                    seasons = emptyList(),
                    seasonsLoading = false,
                    seasonsError = null,
                    selectedSeasonId = null,
                    episodes = emptyList(),
                    episodesLoading = false,
                    episodesError = null,
                    nextUpLoading = false,
                    nextUpError = null,
                    nextUpEpisodeId = null,
                    nextUpEpisode = null,
                )
            }
        is TvItemDetailChange.Series -> reduceTvSeries(current, change.change)
    }
