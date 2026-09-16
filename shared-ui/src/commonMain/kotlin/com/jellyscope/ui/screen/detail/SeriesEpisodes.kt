// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.CardImageAspect
import com.jellyscope.ui.component.SecondaryActionButton
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.rememberCardImageDecode
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_favorite
import com.jellyscope.ui.generated.resources.detail_favorite_cd
import com.jellyscope.ui.generated.resources.detail_mark_unwatched
import com.jellyscope.ui.generated.resources.detail_mark_unwatched_cd
import com.jellyscope.ui.generated.resources.detail_mark_watched
import com.jellyscope.ui.generated.resources.detail_mark_watched_cd
import com.jellyscope.ui.generated.resources.detail_retry
import com.jellyscope.ui.generated.resources.detail_unfavorite
import com.jellyscope.ui.generated.resources.detail_unfavorite_cd
import com.jellyscope.ui.generated.resources.media_info_action
import com.jellyscope.ui.generated.resources.media_info_open
import com.jellyscope.ui.generated.resources.series_episode_cd
import com.jellyscope.ui.generated.resources.series_episodes
import com.jellyscope.ui.generated.resources.series_error
import com.jellyscope.ui.generated.resources.series_no_episodes
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.episodes(
    session: Session,
    episodesState: SeasonEpisodesUiState,
    onRetryEpisodes: () -> Unit,
    onEpisodeFocused: (String) -> Unit,
    onToggleEpisodeWatched: () -> Unit,
    onToggleEpisodeFavorite: () -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onMediaInfoClick: (EpisodeUi) -> Unit,
) {
    item(key = "episodes-title") {
        val horizontalContentPadding = adaptiveHorizontalContentPadding()
        SectionTitle(
            text = stringResource(Res.string.series_episodes),
            modifier =
                Modifier.padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        )
    }
    when (episodesState) {
        SeasonEpisodesUiState.Loading ->
            item(key = "episodes-loading") {
                LoadingRow()
            }
        SeasonEpisodesUiState.Empty ->
            item(key = "episodes-empty") {
                InlineText(stringResource(Res.string.series_no_episodes))
            }
        SeasonEpisodesUiState.Error ->
            item(key = "episodes-error") {
                val horizontalContentPadding = adaptiveHorizontalContentPadding()
                Row(
                    modifier =
                        Modifier.padding(
                            start = horizontalContentPadding.start,
                            end = horizontalContentPadding.end,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.series_error),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = onRetryEpisodes,
                        modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                    ) {
                        Text(stringResource(Res.string.detail_retry))
                    }
                }
            }
        is SeasonEpisodesUiState.Content ->
            items(
                items = episodesState.episodes,
                key = { episode -> episode.itemId },
            ) { episode ->
                EpisodeRow(
                    session = session,
                    episode = episode,
                    onEpisodeFocused = onEpisodeFocused,
                    onToggleEpisodeWatched = onToggleEpisodeWatched,
                    onToggleEpisodeFavorite = onToggleEpisodeFavorite,
                    onSelectEpisodeMediaVersion = onSelectEpisodeMediaVersion,
                    onPlayClick = onPlayClick,
                    onMediaInfoClick = onMediaInfoClick,
                )
            }
    }
}

@Composable
private fun EpisodeRow(
    session: Session,
    episode: EpisodeUi,
    onEpisodeFocused: (String) -> Unit,
    onToggleEpisodeWatched: () -> Unit,
    onToggleEpisodeFavorite: () -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onMediaInfoClick: (EpisodeUi) -> Unit,
) {
    val contentDescription = stringResource(Res.string.series_episode_cd, episode.title)
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val trackSelectionState =
        rememberDetailTrackSelectionState(
            itemId = episode.itemId,
            trackSelection = episode.trackSelection,
        )
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ).heightIn(min = Dimensions.minTouchTarget)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.clickable {
                    onPlayClick(
                        episode.itemId,
                        episode.playAction.startPositionTicks,
                        episode.playAction.mediaSourceId,
                        trackSelectionState.audioStreamIndexForPlay(episode.trackSelection),
                        trackSelectionState.subtitleSelectionForPlay(episode.trackSelection),
                    )
                },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.contentSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EpisodeThumb(
                    session = session,
                    episode = episode,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
                ) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // metadataLine already starts with the "S1 · E1" label, so
                    // render it alone — pairing it with episodeLabel repeated the
                    // season/episode prefix ("S1 · E1 · S1 · E1 · …").
                    episode.metadataLine?.let { metadataLine ->
                        Text(
                            text = metadataLine,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    episode.overview?.let { overview ->
                        Text(
                            text = overview,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            episode.progressFraction?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(Dimensions.progressBarHeight),
                )
            }
            DetailTrackSelectionControls(
                trackSelection = episode.trackSelection,
                selectionState = trackSelectionState,
                versions = episode.versions,
                selectedMediaSourceId = episode.selectedMediaSourceId,
                onSelectMediaVersion = { mediaSourceId ->
                    onSelectEpisodeMediaVersion(episode.itemId, mediaSourceId)
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                episode.restartAction?.let { restartAction ->
                    val restartLabel = playLabel(restartAction.label)
                    SecondaryActionButton(
                        label = restartLabel,
                        icon = Icons.Filled.Replay,
                        active = false,
                        onClick = {
                            onPlayClick(
                                episode.itemId,
                                restartAction.startPositionTicks,
                                restartAction.mediaSourceId,
                                trackSelectionState.audioStreamIndexForPlay(episode.trackSelection),
                                trackSelectionState.subtitleSelectionForPlay(episode.trackSelection),
                            )
                        },
                        contentDescription = restartLabel,
                    )
                }
                EpisodeWatchedButton(
                    episode = episode,
                    onClick = {
                        onEpisodeFocused(episode.itemId)
                        onToggleEpisodeWatched()
                    },
                )
                EpisodeFavoriteButton(
                    episode = episode,
                    onClick = {
                        onEpisodeFocused(episode.itemId)
                        onToggleEpisodeFavorite()
                    },
                )
                if (episode.mediaInfo?.hasAnything == true) {
                    val mediaInfoLabel = stringResource(Res.string.media_info_action)
                    val mediaInfoContentDescription = stringResource(Res.string.media_info_open)
                    SecondaryActionButton(
                        label = mediaInfoLabel,
                        icon = Icons.Outlined.Info,
                        active = false,
                        onClick = { onMediaInfoClick(episode) },
                        contentDescription = mediaInfoContentDescription,
                        showLabel = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeThumb(
    session: Session,
    episode: EpisodeUi,
) {
    Surface(
        modifier =
            Modifier
                .width(Dimensions.listThumbnailWidth.tileScaled())
                .aspectRatio(Dimensions.listThumbnailAspectRatio),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        episode.imageUrl?.let { imageUrl ->
            AsyncImage(
                model =
                    authenticatedImageRequest(
                        imageUrl,
                        session,
                        rememberCardImageDecode(Dimensions.listThumbnailWidth.tileScaled(), CardImageAspect.Wide),
                    ),
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun EpisodeWatchedButton(
    episode: EpisodeUi,
    onClick: () -> Unit,
) {
    val active = episode.isWatched
    val label =
        if (active) {
            stringResource(Res.string.detail_mark_unwatched)
        } else {
            stringResource(Res.string.detail_mark_watched)
        }
    val contentDescription =
        if (active) {
            stringResource(Res.string.detail_mark_unwatched_cd, episode.title)
        } else {
            stringResource(Res.string.detail_mark_watched_cd, episode.title)
        }

    SecondaryActionButton(
        label = label,
        icon = if (active) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
        active = active,
        onClick = onClick,
        enabled = !episode.watchedToggleInFlight,
        contentDescription = contentDescription,
        showLabel = false,
    )
}

@Composable
private fun EpisodeFavoriteButton(
    episode: EpisodeUi,
    onClick: () -> Unit,
) {
    val active = episode.isFavorite
    val label =
        if (active) {
            stringResource(Res.string.detail_unfavorite)
        } else {
            stringResource(Res.string.detail_favorite)
        }
    val contentDescription =
        if (active) {
            stringResource(Res.string.detail_unfavorite_cd, episode.title)
        } else {
            stringResource(Res.string.detail_favorite_cd, episode.title)
        }

    SecondaryActionButton(
        label = label,
        icon = if (active) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        active = active,
        onClick = onClick,
        enabled = !episode.favoriteToggleInFlight,
        contentDescription = contentDescription,
        showLabel = false,
    )
}

@Composable
private fun LoadingRow() {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ).heightIn(min = Dimensions.listThumbnailWidth.tileScaled()),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimensions.progressIndicatorSize),
            strokeWidth = Dimensions.progressIndicatorStroke,
        )
    }
}

@Composable
private fun InlineText(text: String) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Text(
        text = text,
        modifier =
            Modifier.padding(
                start = horizontalContentPadding.start,
                end = horizontalContentPadding.end,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}
