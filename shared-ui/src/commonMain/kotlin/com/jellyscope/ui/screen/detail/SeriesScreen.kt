// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.HeroDetailBackButton
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SeriesScreen(
    session: Session,
    seriesId: String,
    onBack: () -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onPlayEpisode: (
        String,
        Long,
        String?,
        Int?,
        SubtitleSelectionIntent,
        List<String>,
    ) -> Unit = { itemId, startTicks, mediaSourceId, audioStreamIndex, subtitleSelection, _ ->
        onPlayClick(itemId, startTicks, mediaSourceId, audioStreamIndex, subtitleSelection)
    },
    onSeasonRouteSelected: (String) -> Unit = {},
    onItemSelected: (MediaCardUi) -> Unit,
    onSettingsClick: () -> Unit,
    onPersonSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel =
        koinViewModel(
            parameters = { parametersOf(session, seriesId) },
        ),
    ambientColorExtractor: AmbientColorExtractor = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SeriesContent(
        session = session,
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onRetryEpisodes = viewModel::retryEpisodes,
        onSeasonSelected = viewModel::selectSeason,
        onSeasonRouteSelected = { seasonId ->
            viewModel.selectSeason(seasonId)
            onSeasonRouteSelected(seasonId)
        },
        onEpisodeFocused = viewModel::focusEpisode,
        onToggleSeriesFavorite = viewModel::toggleFavorite,
        onToggleEpisodeWatched = viewModel::toggleFocusedEpisodeWatched,
        onToggleEpisodeFavorite = viewModel::toggleFocusedEpisodeFavorite,
        onSelectEpisodeMediaVersion = viewModel::selectEpisodeMediaVersion,
        onPlayClick = onPlayClick,
        onPlayEpisode = onPlayEpisode,
        onItemSelected = onItemSelected,
        onPersonSelected = onPersonSelected,
        ambientColorExtractor = ambientColorExtractor,
        modifier = modifier,
    )
}

@Composable
private fun SeriesContent(
    session: Session,
    state: SeriesUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryEpisodes: () -> Unit,
    onSeasonSelected: (String) -> Unit,
    onSeasonRouteSelected: (String) -> Unit,
    onEpisodeFocused: (String) -> Unit,
    onToggleSeriesFavorite: () -> Unit,
    onToggleEpisodeWatched: () -> Unit,
    onToggleEpisodeFavorite: () -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onPlayEpisode: (String, Long, String?, Int?, SubtitleSelectionIntent, List<String>) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    ambientColorExtractor: AmbientColorExtractor,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SeriesUiState.Loading ->
            SeriesLoading(
                onBack = onBack,
                modifier = modifier,
            )
        is SeriesUiState.Error ->
            SeriesError(
                retryable = state.retryable,
                onBack = onBack,
                onRetry = onRetry,
                modifier = modifier,
            )
        is SeriesUiState.Content ->
            SeriesBody(
                session = session,
                content = state.content,
                onBack = onBack,
                onRetryEpisodes = onRetryEpisodes,
                onSeasonSelected = onSeasonSelected,
                onSeasonRouteSelected = onSeasonRouteSelected,
                onEpisodeFocused = onEpisodeFocused,
                onToggleSeriesFavorite = onToggleSeriesFavorite,
                onToggleEpisodeWatched = onToggleEpisodeWatched,
                onToggleEpisodeFavorite = onToggleEpisodeFavorite,
                onSelectEpisodeMediaVersion = onSelectEpisodeMediaVersion,
                onPlayClick = onPlayClick,
                onPlayEpisode = onPlayEpisode,
                onItemSelected = onItemSelected,
                onPersonSelected = onPersonSelected,
                ambientColorExtractor = ambientColorExtractor,
                modifier = modifier,
            )
    }
}

@Composable
private fun SeriesBody(
    session: Session,
    content: SeriesContentUi,
    onBack: () -> Unit,
    onRetryEpisodes: () -> Unit,
    onSeasonSelected: (String) -> Unit,
    onSeasonRouteSelected: (String) -> Unit,
    onEpisodeFocused: (String) -> Unit,
    onToggleSeriesFavorite: () -> Unit,
    onToggleEpisodeWatched: () -> Unit,
    onToggleEpisodeFavorite: () -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onPlayEpisode: (String, Long, String?, Int?, SubtitleSelectionIntent, List<String>) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    ambientColorExtractor: AmbientColorExtractor,
    modifier: Modifier = Modifier,
) {
    val backContentDescription = stringResource(Res.string.detail_back)
    var infoEpisode by remember { mutableStateOf<EpisodeUi?>(null) }
    var ambientColor by remember(content.series.itemId) { mutableStateOf<Color?>(null) }
    val scope = rememberCoroutineScope()

    if (LocalWindowWidthTier.current != WindowWidthTier.Compact) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDetailInteractionMode provides DetailInteractionMode.Touch) {
            AdaptiveSeriesContent(
                session = session,
                content = content,
                onBack = onBack,
                onPlay = onPlayEpisode,
                onSeasonSelected = onSeasonRouteSelected,
                onToggleFavorite = onToggleSeriesFavorite,
                onSelectEpisodeMediaVersion = onSelectEpisodeMediaVersion,
                onRelatedItemSelected = onItemSelected,
                onPersonSelected = onPersonSelected,
                ambientColorExtractor = ambientColorExtractor,
                modifier = modifier,
            )
        }
        return
    }

    AmbientBackground(
        ambientColor = ambientColor,
        modifier = modifier.fillMaxSize(),
    ) {
        AmbientLayer(ambientColor = ambientColor)
        Box(modifier = Modifier.fillMaxSize()) {
            SeriesBodyCompact(
                session = session,
                content = content,
                onRetryEpisodes = onRetryEpisodes,
                onSeasonSelected = onSeasonSelected,
                onEpisodeFocused = onEpisodeFocused,
                onToggleSeriesFavorite = onToggleSeriesFavorite,
                onToggleEpisodeWatched = onToggleEpisodeWatched,
                onToggleEpisodeFavorite = onToggleEpisodeFavorite,
                onSelectEpisodeMediaVersion = onSelectEpisodeMediaVersion,
                onPlayClick = onPlayClick,
                onItemSelected = onItemSelected,
                onPersonSelected = onPersonSelected,
                onMediaInfoClick = { episode -> infoEpisode = episode },
                onBackdropLoaded = { image ->
                    scope.launch {
                        ambientColor =
                            ambientColorExtractor.extract(
                                image,
                                ambientImageCacheKey(session, content.series.itemId, content.series.backdropUrl),
                            )
                    }
                },
            )
            HeroDetailBackButton(
                contentDescription = backContentDescription,
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopStart),
            )
            val mediaInfoEpisode = infoEpisode
            val mediaInfo = mediaInfoEpisode?.mediaInfo
            if (mediaInfoEpisode != null && mediaInfo != null && mediaInfo.hasAnything) {
                MediaInfoSheet(
                    title = mediaInfoEpisode.title,
                    mediaInfo = mediaInfo,
                    onDismiss = { infoEpisode = null },
                )
            }
        }
    }
}
