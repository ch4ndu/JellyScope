// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger

internal fun List<SeasonUi>.sortedForTabs(): List<SeasonUi> =
    sortedWith(
        compareBy<SeasonUi> { season -> season.indexNumber ?: Int.MAX_VALUE }
            .thenBy { season -> season.title },
    )

internal fun List<SeasonUi>.initialSeason(): SeasonUi? =
    firstOrNull { season -> (season.unplayedCount ?: 0) > 0 }
        ?: firstOrNull()

internal fun SeriesContentUi.seasonIndexFor(seasonId: String): Int? = seasons.firstOrNull { season -> season.id == seasonId }?.indexNumber

internal fun List<MediaItem>.sortedForStrip(): List<MediaItem> =
    sortedWith(
        compareBy<MediaItem> { item -> item.parentIndexNumber ?: Int.MAX_VALUE }
            .thenBy { item -> item.indexNumber ?: Int.MAX_VALUE }
            .thenBy { item -> item.name },
    )

internal fun List<EpisodeUi>.toEpisodesState(): SeasonEpisodesUiState =
    if (isEmpty()) {
        SeasonEpisodesUiState.Empty
    } else {
        SeasonEpisodesUiState.Content(this)
    }

internal fun List<EpisodeUi>.nextPlayableEpisode(): EpisodeUi? =
    firstOrNull { episode -> !episode.isWatched && episode.playAction.startPositionTicks > 0L }
        ?: firstOrNull { episode -> !episode.isWatched }
        ?: firstOrNull()

internal fun EpisodeUi.withWatched(played: Boolean): EpisodeUi {
    val updatedPlayAction =
        if (played) {
            playAction.copy(label = DetailPlayLabel.StartOver, startPositionTicks = 0L)
        } else {
            playAction.copy(label = DetailPlayLabel.Start, startPositionTicks = 0L)
        }
    return copy(
        playAction = updatedPlayAction,
        restartAction = if (played) null else restartAction,
        isWatched = played,
        progressFraction = if (played) null else progressFraction,
    )
}

internal fun EpisodeUi.withWatchedRollbackFrom(previous: EpisodeUi): EpisodeUi =
    copy(
        playAction =
            playAction.copy(
                label = previous.playAction.label,
                startPositionTicks = previous.playAction.startPositionTicks,
            ),
        restartAction = previous.restartAction?.copy(mediaSourceId = selectedMediaSourceId),
        isWatched = previous.isWatched,
        watchedToggleInFlight = false,
        progressFraction = previous.progressFraction,
    )

internal fun EpisodeUi.withSelectedMediaVersion(version: MediaVersionUi): EpisodeUi =
    copy(
        selectedMediaSourceId = version.id,
        streamBadges = version.streamBadges,
        mediaInfo = version.mediaInfo,
        playAction = playAction.copy(mediaSourceId = version.id),
        restartAction = restartAction?.copy(mediaSourceId = version.id),
        trackSelection = version.trackSelection,
        versions = versions.map { candidate -> if (candidate.id == version.id) version else candidate },
    )

internal fun EpisodeUi.withSourceProjectionFrom(source: EpisodeUi): EpisodeUi =
    copy(
        selectedMediaSourceId = source.selectedMediaSourceId,
        streamBadges = source.streamBadges,
        mediaInfo = source.mediaInfo,
        playAction = playAction.copy(mediaSourceId = source.selectedMediaSourceId),
        restartAction = restartAction?.copy(mediaSourceId = source.selectedMediaSourceId),
        trackSelection = source.trackSelection,
        versions = source.versions,
    )

internal val seriesViewModelLogger = diagnosticLogger(DiagnosticTag.SeriesViewModel)
