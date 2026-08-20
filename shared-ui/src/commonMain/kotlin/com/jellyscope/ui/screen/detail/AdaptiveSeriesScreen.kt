// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.component.AdaptiveCenteredSpinner
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AdaptiveSeriesScreen(
    session: Session,
    seriesId: String,
    onBack: () -> Unit,
    onPlay: (String, Long, String?, Int?, SubtitleSelectionIntent, List<String>) -> Unit,
    onSeasonSelected: (String) -> Unit,
    onRelatedItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlayRelatedDirect: ((MediaCardUi) -> Unit)? = null,
    viewModel: SeriesViewModel =
        koinViewModel(
            parameters = { parametersOf(session, seriesId) },
        ),
    ambientColorExtractor: AmbientColorExtractor = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val seriesState = state) {
        SeriesUiState.Loading -> AdaptiveCenteredSpinner(modifier = modifier)
        is SeriesUiState.Error ->
            AdaptiveSeriesError(
                retryable = seriesState.retryable,
                onRetry = viewModel::retry,
                modifier = modifier,
            )
        is SeriesUiState.Content ->
            AdaptiveSeriesContent(
                session = session,
                content = seriesState.content,
                onBack = onBack,
                onPlay = onPlay,
                onSeasonSelected = { seasonId ->
                    viewModel.selectSeason(seasonId)
                    onSeasonSelected(seasonId)
                },
                onToggleFavorite = viewModel::toggleSeriesFavorite,
                onSelectEpisodeMediaVersion = viewModel::selectEpisodeMediaVersion,
                onRelatedItemSelected = onRelatedItemSelected,
                onPersonSelected = onPersonSelected,
                modifier = modifier,
                onPlayRelatedDirect = onPlayRelatedDirect,
                ambientColorExtractor = ambientColorExtractor,
            )
    }
}

@Composable
fun AdaptiveSeasonScreen(
    session: Session,
    seriesId: String,
    initialSeasonId: String,
    onBack: () -> Unit,
    onPlay: (String, Long, String?, Int?, SubtitleSelectionIntent, List<String>) -> Unit,
    onSeasonSelected: (String) -> Unit,
    onPersonSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel =
        koinViewModel(
            parameters = { parametersOf(session, seriesId) },
        ),
    ambientColorExtractor: AmbientColorExtractor = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedSeasonId = (state as? SeriesUiState.Content)?.content?.selectedSeasonId

    LaunchedEffect(initialSeasonId, selectedSeasonId) {
        if (initialSeasonId.isNotBlank() && selectedSeasonId != null && selectedSeasonId != initialSeasonId) {
            viewModel.selectSeason(initialSeasonId)
        }
    }

    // Refresh the visible season's episodes (watched / resume state) when the
    // screen becomes active again after the player. Mobile: the nav entry resumes
    // (ON_RESUME); TV: the route content is recomposed on return (LaunchedEffect).
    // refresh() is a no-op until the first load produced Content, so neither path
    // double-loads on first entry.
    OnResumeEffect(viewModel::refresh)
    LaunchedEffect(viewModel) { viewModel.refresh() }

    when (val seriesState = state) {
        SeriesUiState.Loading -> AdaptiveCenteredSpinner(modifier = modifier)
        is SeriesUiState.Error ->
            AdaptiveSeriesError(
                retryable = seriesState.retryable,
                onRetry = viewModel::retry,
                modifier = modifier,
            )
        is SeriesUiState.Content ->
            AdaptiveSeasonContent(
                session = session,
                content = seriesState.content,
                focusedEpisodeId = viewModel.focusedEpisodeId,
                onBack = onBack,
                onPlayEpisode = { episode, audioStreamIndex, subtitleSelection ->
                    onPlay(
                        episode.itemId,
                        episode.playAction.startPositionTicks,
                        episode.playAction.mediaSourceId,
                        audioStreamIndex,
                        subtitleSelection,
                        seriesState.content.episodeQueueFrom(episode.itemId),
                    )
                },
                onRestartEpisode = { episode, audioStreamIndex, subtitleSelection ->
                    episode.restartAction?.let { restart ->
                        onPlay(
                            episode.itemId,
                            restart.startPositionTicks,
                            restart.mediaSourceId,
                            audioStreamIndex,
                            subtitleSelection,
                            seriesState.content.episodeQueueFrom(episode.itemId),
                        )
                    }
                },
                onToggleWatched = viewModel::toggleFocusedEpisodeWatched,
                onToggleFavorite = viewModel::toggleFocusedEpisodeFavorite,
                onSeasonSelected = { seasonId ->
                    viewModel.selectSeason(seasonId)
                    onSeasonSelected(seasonId)
                },
                onFocusedEpisode = viewModel::focusEpisode,
                onSelectEpisodeMediaVersion = viewModel::selectEpisodeMediaVersion,
                onRetryEpisodes = viewModel::retryEpisodes,
                onPersonSelected = onPersonSelected,
                modifier = modifier,
                ambientColorExtractor = ambientColorExtractor,
            )
    }
}
