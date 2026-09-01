// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
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
import com.jellyscope.ui.generated.resources.detail_backdrop_cd
import com.jellyscope.ui.generated.resources.detail_favorite
import com.jellyscope.ui.generated.resources.detail_favorite_cd
import com.jellyscope.ui.generated.resources.detail_mark_unwatched
import com.jellyscope.ui.generated.resources.detail_mark_unwatched_cd
import com.jellyscope.ui.generated.resources.detail_mark_watched
import com.jellyscope.ui.generated.resources.detail_mark_watched_cd
import com.jellyscope.ui.generated.resources.detail_overview
import com.jellyscope.ui.generated.resources.detail_play_cd
import com.jellyscope.ui.generated.resources.detail_poster_cd
import com.jellyscope.ui.generated.resources.detail_trailer
import com.jellyscope.ui.generated.resources.detail_unfavorite
import com.jellyscope.ui.generated.resources.detail_unfavorite_cd
import com.jellyscope.ui.generated.resources.media_info_action
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DetailBodyCompact(
    session: Session,
    detail: DetailUi,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectMediaVersion: (String) -> Unit,
    subtitleActions: DetailSubtitlePickerActions?,
    downloadEntryState: DetailDownloadEntryState?,
    downloadActionEnabled: Boolean,
    downloadFixedAvailable: Boolean,
    onDownloadClick: () -> Unit,
    onMediaInfoClick: () -> Unit,
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
            val contentDescription = stringResource(Res.string.detail_backdrop_cd, detail.title)
            Backdrop(
                session = session,
                imageUrl = detail.backdropUrl,
                contentDescription = contentDescription,
                onImageLoaded = onBackdropLoaded,
            )
        }
        item(key = "header") {
            HeaderBlock(
                session = session,
                detail = detail,
                onPlayClick = onPlayClick,
                onToggleWatched = onToggleWatched,
                onToggleFavorite = onToggleFavorite,
                onSelectMediaVersion = onSelectMediaVersion,
                subtitleActions = subtitleActions,
                downloadEntryState = downloadEntryState,
                downloadActionEnabled = downloadActionEnabled,
                downloadFixedAvailable = downloadFixedAvailable,
                onDownloadClick = onDownloadClick,
                onMediaInfoClick = onMediaInfoClick,
            )
        }
        if (detail.genresLine != null ||
            detail.directedByLine != null ||
            detail.officialRating != null ||
            detail.communityRating != null ||
            detail.criticRatingText != null ||
            detail.imdbUrl != null ||
            detail.tmdbUrl != null
        ) {
            item(key = "metadata") {
                DetailMetadataSection(detail)
            }
        }
        detail.overview?.let { overview ->
            item(key = "overview") {
                TextSection(
                    title = stringResource(Res.string.detail_overview),
                    body = overview,
                )
            }
        }
        if (detail.castAndCrew.isNotEmpty()) {
            item(key = "cast") {
                CastAndCrewSection(
                    session = session,
                    people = detail.castAndCrew,
                    onPersonSelected = onPersonSelected,
                )
            }
        }
        detail.relatedGroups.forEach { group ->
            if (group.items.isNotEmpty()) {
                item(key = "related-${group.kind}-${group.label.orEmpty()}") {
                    RelatedSection(
                        session = session,
                        title = relatedGroupTitle(group.kind, group.label),
                        items = group.items,
                        onItemSelected = onItemSelected,
                    )
                }
            }
        }
        // Trailing loading indicator: visible while ANY related shelf is still
        // being fetched (after whatever has loaded so far).
        if (detail.relatedLoading) {
            item(key = "related-loading") {
                RelatedLoadingSection(showTitle = detail.relatedGroups.isEmpty())
            }
        }
    }
}

@Composable
private fun HeaderBlock(
    session: Session,
    detail: DetailUi,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectMediaVersion: (String) -> Unit,
    subtitleActions: DetailSubtitlePickerActions?,
    downloadEntryState: DetailDownloadEntryState?,
    downloadActionEnabled: Boolean,
    downloadFixedAvailable: Boolean,
    onDownloadClick: () -> Unit,
    onMediaInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val trackSelectionState =
        rememberDetailTrackSelectionState(
            itemId = detail.itemId,
            trackSelection = detail.trackSelection,
        )

    Column(
        modifier =
            modifier
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
                imageUrl = detail.posterUrl,
                contentDescription = stringResource(Res.string.detail_poster_cd, detail.title),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
            ) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                detail.headerLine?.let { headerLine ->
                    Text(
                        text = headerLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                detail.metadataLine?.let { metadataLine ->
                    Text(
                        text = metadataLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                detail.tagline?.let { tagline ->
                    Text(
                        text = tagline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BadgeRow(detail)
            }
        }
        DetailTrackSelectionControls(
            trackSelection = detail.trackSelection,
            selectionState = trackSelectionState,
            versions = detail.versions,
            selectedMediaSourceId = detail.selectedMediaSourceId,
            onSelectMediaVersion = onSelectMediaVersion,
            subtitleActions = subtitleActions,
        )
        ActionButtons(
            detail = detail,
            trackSelectionState = trackSelectionState,
            onPlayClick = onPlayClick,
            onToggleWatched = onToggleWatched,
            onToggleFavorite = onToggleFavorite,
            downloadEntryState = downloadEntryState,
            downloadActionEnabled = downloadActionEnabled,
            downloadFixedAvailable = downloadFixedAvailable,
            onDownloadClick = onDownloadClick,
            onMediaInfoClick = onMediaInfoClick,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BadgeRow(
    detail: DetailUi,
    modifier: Modifier = Modifier,
) {
    val badges = detail.streamBadges

    if (badges.isEmpty()) {
        return
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        badges.forEach { badge -> InfoBadge(text = badge) }
    }
}

@Composable
internal fun InfoBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val palette = LocalJellyfinPalette.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (accent) palette.accentCoral else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier =
                Modifier.padding(
                    horizontal = Dimensions.badgeHorizontalPadding,
                    vertical = Dimensions.badgeVerticalPadding,
                ),
            color = if (accent) palette.onFocusedLight else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ActionButtons(
    detail: DetailUi,
    trackSelectionState: DetailTrackSelectionState,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    downloadEntryState: DetailDownloadEntryState?,
    downloadActionEnabled: Boolean,
    downloadFixedAvailable: Boolean,
    onDownloadClick: () -> Unit,
    onMediaInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = playLabel(detail.playAction.label)
    val contentDescription = stringResource(Res.string.detail_play_cd, detail.title)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        PrimaryPlayButton(
            label = label,
            timeLeftText = detail.timeLeftText,
            onClick = {
                onPlayClick(
                    detail.itemId,
                    detail.playAction.startPositionTicks,
                    detail.playAction.mediaSourceId,
                    trackSelectionState.audioStreamIndexForPlay(detail.trackSelection),
                    trackSelectionState.subtitleSelectionForPlay(detail.trackSelection),
                )
            },
            contentDescription = contentDescription,
            modifier =
                Modifier
                    .fillMaxWidth(),
        )
        detail.timeLeftText?.let { timeLeft ->
            Text(
                text = timeLeft,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // FlowRow, not Row: in a Row the labelled Restart/Trailer buttons were
        // squeezed until their text wrapped to two lines. Matches the adaptive
        // hero's action row.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        ) {
            detail.restartAction?.let { restartAction ->
                val restartLabel = playLabel(restartAction.label)
                SecondaryActionButton(
                    label = restartLabel,
                    icon = Icons.Filled.Replay,
                    active = false,
                    onClick = {
                        onPlayClick(
                            detail.itemId,
                            restartAction.startPositionTicks,
                            restartAction.mediaSourceId,
                            trackSelectionState.audioStreamIndexForPlay(detail.trackSelection),
                            trackSelectionState.subtitleSelectionForPlay(detail.trackSelection),
                        )
                    },
                    contentDescription = restartLabel,
                )
            }
            WatchedButton(
                detail = detail,
                onToggleWatched = onToggleWatched,
            )
            FavoriteButton(
                detail = detail,
                onToggleFavorite = onToggleFavorite,
            )
            downloadEntryState?.let { entryState ->
                OriginalDownloadEntryButton(
                    state = entryState,
                    enabled = downloadActionEnabled,
                    fixedAvailable = downloadFixedAvailable,
                    onClick = onDownloadClick,
                )
            }
            if (detail.mediaInfo?.hasAnything == true) {
                val infoLabel = stringResource(Res.string.media_info_action)
                SecondaryActionButton(
                    label = infoLabel,
                    icon = Icons.Outlined.Info,
                    active = false,
                    onClick = onMediaInfoClick,
                    contentDescription = infoLabel,
                    showLabel = false,
                )
            }
            detail.trailerUrl?.let { url ->
                val uriHandler = LocalUriHandler.current
                val trailerLabel = stringResource(Res.string.detail_trailer)
                SecondaryActionButton(
                    label = trailerLabel,
                    icon = Icons.Outlined.Movie,
                    active = false,
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    contentDescription = trailerLabel,
                )
            }
        }
    }
}

@Composable
private fun WatchedButton(
    detail: DetailUi,
    onToggleWatched: () -> Unit,
) {
    val active = detail.isWatched
    val label =
        if (active) {
            stringResource(Res.string.detail_mark_unwatched)
        } else {
            stringResource(Res.string.detail_mark_watched)
        }
    val contentDescription =
        if (active) {
            stringResource(Res.string.detail_mark_unwatched_cd, detail.title)
        } else {
            stringResource(Res.string.detail_mark_watched_cd, detail.title)
        }

    SecondaryActionButton(
        label = label,
        icon = if (active) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
        active = active,
        onClick = onToggleWatched,
        enabled = !detail.watchedToggleInFlight,
        contentDescription = contentDescription,
        showLabel = false,
    )
}

@Composable
private fun FavoriteButton(
    detail: DetailUi,
    onToggleFavorite: () -> Unit,
) {
    val active = detail.isFavorite
    val label =
        if (active) {
            stringResource(Res.string.detail_unfavorite)
        } else {
            stringResource(Res.string.detail_favorite)
        }
    val contentDescription =
        if (active) {
            stringResource(Res.string.detail_unfavorite_cd, detail.title)
        } else {
            stringResource(Res.string.detail_favorite_cd, detail.title)
        }

    SecondaryActionButton(
        label = label,
        icon = if (active) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        active = active,
        onClick = onToggleFavorite,
        enabled = !detail.favoriteToggleInFlight,
        contentDescription = contentDescription,
        showLabel = false,
    )
}
