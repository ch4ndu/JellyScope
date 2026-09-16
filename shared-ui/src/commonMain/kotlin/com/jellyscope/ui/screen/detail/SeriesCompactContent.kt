// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import coil3.Image
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.PrimaryPlayButton
import com.jellyscope.ui.component.SecondaryActionButton
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.heroDetailTopPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_critic_rating
import com.jellyscope.ui.generated.resources.detail_favorite
import com.jellyscope.ui.generated.resources.detail_favorite_cd
import com.jellyscope.ui.generated.resources.detail_genres
import com.jellyscope.ui.generated.resources.detail_overview
import com.jellyscope.ui.generated.resources.detail_play_cd
import com.jellyscope.ui.generated.resources.detail_play_start
import com.jellyscope.ui.generated.resources.detail_related
import com.jellyscope.ui.generated.resources.detail_unfavorite
import com.jellyscope.ui.generated.resources.detail_unfavorite_cd
import com.jellyscope.ui.generated.resources.series_seasons
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SeriesBodyCompact(
    session: Session,
    content: SeriesContentUi,
    onRetryEpisodes: () -> Unit,
    onSeasonSelected: (String) -> Unit,
    onEpisodeFocused: (String) -> Unit,
    onToggleSeriesFavorite: () -> Unit,
    onToggleEpisodeWatched: () -> Unit,
    onToggleEpisodeFavorite: () -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    onMediaInfoClick: (EpisodeUi) -> Unit,
    onBackdropLoaded: (Image) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier =
            modifier
                .fillMaxSize()
                .desktopScrollInput(listState, DesktopScrollOrientation.Vertical),
        contentPadding =
            PaddingValues(
                top = heroDetailTopPadding(),
                bottom = appNavigationBarContentPadding(),
            ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.detailSectionSpacing),
    ) {
        item(key = "backdrop") {
            Backdrop(
                session = session,
                imageUrl = content.series.backdropUrl ?: content.series.posterUrl,
                contentDescription = null,
                onImageLoaded = onBackdropLoaded,
            )
        }
        item(key = "header") {
            SeriesHeader(
                session = session,
                content = content,
                onToggleFavorite = onToggleSeriesFavorite,
                onPlayClick = onPlayClick,
                onSelectEpisodeMediaVersion = onSelectEpisodeMediaVersion,
            )
        }
        content.series.overview?.let { overview ->
            item(key = "overview") {
                TextSection(
                    title = stringResource(Res.string.detail_overview),
                    body = overview,
                )
            }
        }
        item(key = "seasons") {
            SeasonsRow(
                seasons = content.seasons,
                selectedSeasonId = content.selectedSeasonId,
                onSeasonSelected = onSeasonSelected,
            )
        }
        episodes(
            session = session,
            episodesState = content.episodesState,
            onRetryEpisodes = onRetryEpisodes,
            onEpisodeFocused = onEpisodeFocused,
            onToggleEpisodeWatched = onToggleEpisodeWatched,
            onToggleEpisodeFavorite = onToggleEpisodeFavorite,
            onSelectEpisodeMediaVersion = onSelectEpisodeMediaVersion,
            onPlayClick = onPlayClick,
            onMediaInfoClick = onMediaInfoClick,
        )
        if (content.series.castAndCrew.isNotEmpty()) {
            item(key = "cast") {
                CastAndCrewSection(
                    session = session,
                    people = content.series.castAndCrew,
                    onPersonSelected = onPersonSelected,
                    cardSizing = CastAndCrewCardSizing.SeriesPoster,
                )
            }
        }
        if (content.related.isNotEmpty()) {
            item(key = "related") {
                RelatedSection(
                    session = session,
                    title = stringResource(Res.string.detail_related),
                    items = content.related,
                    onItemSelected = onItemSelected,
                )
            }
        }
    }
}

@Composable
private fun SeriesHeader(
    session: Session,
    content: SeriesContentUi,
    onToggleFavorite: () -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit,
) {
    val series = content.series
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
            verticalAlignment = Alignment.Top,
        ) {
            Poster(
                session = session,
                imageUrl = series.posterUrl,
                contentDescription = null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
            ) {
                Text(
                    text = series.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                series.metadataLine?.let { metadata ->
                    Text(
                        text = metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                series.genresLine?.let { genres ->
                    Text(
                        text = stringResource(Res.string.detail_genres, genres),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                listOfNotNull(
                    series.officialRating,
                    series.communityRating,
                    series.criticRatingText?.let { rating ->
                        stringResource(Res.string.detail_critic_rating, rating)
                    },
                ).takeIf { ratings -> ratings.isNotEmpty() }?.let { ratings ->
                    Text(
                        text = ratings.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        SeriesActions(
            content = content,
            onToggleFavorite = onToggleFavorite,
            onPlayClick = onPlayClick,
            onSelectEpisodeMediaVersion = onSelectEpisodeMediaVersion,
        )
    }
}

@Composable
private fun SeriesActions(
    content: SeriesContentUi,
    onToggleFavorite: () -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit,
) {
    val episode = content.seriesPlayEpisode
    val action = episode?.playAction
    val trackSelectionState =
        episode?.let { nextEpisode ->
            rememberDetailTrackSelectionState(
                itemId = nextEpisode.itemId,
                trackSelection = nextEpisode.trackSelection,
            )
        }
    val actionLabel =
        action?.label?.let { label -> playLabel(label) }
            ?: stringResource(Res.string.detail_play_start)
    val favoriteActive = content.series.isFavorite
    val favoriteLabel =
        if (favoriteActive) {
            stringResource(Res.string.detail_unfavorite)
        } else {
            stringResource(Res.string.detail_favorite)
        }
    val favoriteContentDescription =
        if (favoriteActive) {
            stringResource(Res.string.detail_unfavorite_cd, content.series.title)
        } else {
            stringResource(Res.string.detail_favorite_cd, content.series.title)
        }
    val playContentDescription = stringResource(Res.string.detail_play_cd, content.series.title)

    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        PrimaryPlayButton(
            label = actionLabel,
            timeLeftText = episode?.timeLeftText,
            onClick = {
                if (episode != null && action != null && trackSelectionState != null) {
                    onPlayClick(
                        episode.itemId,
                        action.startPositionTicks,
                        action.mediaSourceId,
                        trackSelectionState.audioStreamIndexForPlay(episode.trackSelection),
                        trackSelectionState.subtitleSelectionForPlay(episode.trackSelection),
                    )
                }
            },
            enabled = episode != null && action != null,
            contentDescription = playContentDescription,
            modifier = Modifier.fillMaxWidth(),
        )
        episode?.timeLeftText?.let { timeLeft ->
            Text(
                text = timeLeft,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (episode != null && trackSelectionState != null) {
            DetailTrackSelectionControls(
                trackSelection = episode.trackSelection,
                selectionState = trackSelectionState,
                versions = episode.versions,
                selectedMediaSourceId = episode.selectedMediaSourceId,
                onSelectMediaVersion = { mediaSourceId ->
                    onSelectEpisodeMediaVersion(episode.itemId, mediaSourceId)
                },
                showStaticAudioText = false,
            )
        }
        SecondaryActionButton(
            label = favoriteLabel,
            icon = if (favoriteActive) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            active = favoriteActive,
            onClick = onToggleFavorite,
            enabled = !content.series.favoriteToggleInFlight,
            contentDescription = favoriteContentDescription,
            showLabel = false,
        )
    }
}

@Composable
private fun SeasonsRow(
    seasons: List<SeasonUi>,
    selectedSeasonId: String?,
    onSeasonSelected: (String) -> Unit,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val rowListState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        SectionTitle(
            text = stringResource(Res.string.series_seasons),
            modifier =
                Modifier.padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        )
        LazyRow(
            state = rowListState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .desktopScrollInput(rowListState, DesktopScrollOrientation.Horizontal),
            contentPadding = horizontalContentPadding.asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        ) {
            items(
                items = seasons,
                key = { season -> season.id },
            ) { season ->
                FilterChip(
                    selected = season.id == selectedSeasonId,
                    onClick = { onSeasonSelected(season.id) },
                    label = { Text(season.title) },
                    modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                )
            }
        }
    }
}
