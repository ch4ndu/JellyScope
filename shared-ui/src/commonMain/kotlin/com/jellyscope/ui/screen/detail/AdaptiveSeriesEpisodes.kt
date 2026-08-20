// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.component.AdaptiveSpinner
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.DetailTitleStyle
import com.jellyscope.ui.component.FocusableBox
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.media_info_action
import com.jellyscope.ui.generated.resources.media_info_open
import com.jellyscope.ui.generated.resources.tv_add_favorite
import com.jellyscope.ui.generated.resources.tv_details_error
import com.jellyscope.ui.generated.resources.tv_episodes
import com.jellyscope.ui.generated.resources.tv_mark_unwatched
import com.jellyscope.ui.generated.resources.tv_mark_watched
import com.jellyscope.ui.generated.resources.tv_no_episodes
import com.jellyscope.ui.generated.resources.tv_overview_fallback
import com.jellyscope.ui.generated.resources.tv_remove_favorite
import com.jellyscope.ui.generated.resources.tv_restart
import com.jellyscope.ui.generated.resources.tv_retry
import com.jellyscope.ui.generated.resources.tv_row_error
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FocusedEpisodeMetadata(
    series: SeriesHeaderUi,
    episodesState: SeasonEpisodesUiState,
    focusedEpisodeId: StateFlow<String?>,
    defaultEpisode: EpisodeUi?,
) {
    val currentFocusedEpisodeId by focusedEpisodeId.collectAsStateWithLifecycle()
    val episode = episodesState.focusedEpisodeOrDefault(currentFocusedEpisodeId, defaultEpisode)

    Column(
        modifier =
            Modifier
                .padding(horizontal = detailHorizontalInset())
                .fillMaxWidth(DetailDimens.seasonMetadataTextWidthFraction),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.seasonMetadataGap, Alignment.Top),
    ) {
        DetailText(
            text = series.title,
            style = DetailBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 1,
        )
        DetailText(
            text = episode?.title ?: stringResource(Res.string.tv_episodes),
            style = DetailSeasonHeaderStyle,
            maxLines = 2,
            minLines = 2,
        )
        episode?.metadataLine?.let { metadata ->
            DetailText(
                text = metadata,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
        DetailText(
            text = episode?.overview ?: stringResource(Res.string.tv_overview_fallback),
            style = DetailBodyStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
            minLines = 3,
        )
    }
}

@Composable
internal fun EpisodeStrip(
    session: Session,
    episodesState: SeasonEpisodesUiState,
    focusedEpisodeId: StateFlow<String?>,
    defaultFocusedEpisodeId: String?,
    selectedSeasonId: String?,
    episodesFocusedOnce: Boolean,
    onEpisodesFocusedOnce: () -> Unit,
    selectedTabRequester: FocusRequester?,
    episodeFocus: DetailFocusContainer,
    episodeActionsRequester: FocusRequester?,
    horizontalBringIntoViewSpec: androidx.compose.foundation.gestures.BringIntoViewSpec,
    onFocusedEpisode: (String) -> Unit,
    onEpisodeSelected: (EpisodeUi, String?) -> Unit,
    onRetryEpisodes: () -> Unit,
) {
    when (episodesState) {
        SeasonEpisodesUiState.Loading ->
            EpisodeStripFrame {
                Box(modifier = Modifier.padding(horizontal = detailHorizontalInset())) {
                    AdaptiveSpinner(size = DetailDimens.playerIconSize)
                }
            }
        SeasonEpisodesUiState.Empty ->
            EpisodeStripFrame {
                DetailText(
                    text = stringResource(Res.string.tv_no_episodes),
                    modifier = Modifier.padding(horizontal = detailHorizontalInset()),
                    color = LocalJellyfinPalette.current.textSecondary,
                )
            }
        SeasonEpisodesUiState.Error ->
            EpisodeStripFrame {
                Row(
                    modifier = Modifier.padding(horizontal = detailHorizontalInset()),
                    horizontalArrangement = Arrangement.spacedBy(DetailDimens.itemGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailText(
                        text = stringResource(Res.string.tv_row_error),
                        color = LocalJellyfinPalette.current.error,
                    )
                    RetryButton(
                        text = stringResource(Res.string.tv_retry),
                        onClick = onRetryEpisodes,
                        modifier = Modifier.width(DetailDimens.detailBackButtonWidth),
                    )
                }
            }
        is SeasonEpisodesUiState.Content -> {
            val firstEpisodeId = episodesState.episodes.firstOrNull()?.itemId
            val defaultEntryEpisodeId = defaultFocusedEpisodeId ?: firstEpisodeId
            // Row-owned: lazy cards are disposed while browsing, so the
            // entry focus request must not live on the recreated card.
            var restoreExpired by remember(defaultEntryEpisodeId) { mutableStateOf(false) }
            val episodeIds =
                remember(episodesState.episodes) {
                    episodesState.episodes.mapTo(mutableSetOf()) { episode -> episode.itemId }
                }
            val currentFocusedEpisodeId by focusedEpisodeId.collectAsStateWithLifecycle()
            val effectiveFocusedEpisodeId =
                effectiveFocusedEpisodeId(
                    focusedEpisodeId = currentFocusedEpisodeId,
                    validEpisodeIds = episodeIds,
                    defaultEpisodeId = defaultEntryEpisodeId,
                )
            // Handed to every card in place of the id. Its identity never changes, so
            // a focus move stops being a changed parameter for cards that render
            // nothing from it; only `selected` still moves, and only for the two
            // cards whose selection actually flipped.
            val focusedEpisodeIdSnapshot = rememberUpdatedState(effectiveFocusedEpisodeId)
            val currentFocusedEpisodeIdProvider = remember { { focusedEpisodeIdSnapshot.value } }
            // Fresh scroll state per season (starts at the first episode) so
            // the DOWN-target card is composed without an explicit scroll —
            // this avoids the entry scroll fighting the center-into-view and
            // removes the visible ribbon wiggle on season switch.
            val rowListState = key(selectedSeasonId) { rememberLazyListState() }

            FocusableRibbon(
                title = stringResource(Res.string.tv_episodes),
                items = episodesState.episodes,
                key = { episode -> episode.itemId },
                horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                modifier = Modifier.detailFocusGroup(),
                rowListState = rowListState,
                rowModifier =
                    Modifier
                        .then(
                            episodeActionsRequester?.let { requester ->
                                Modifier.detailFocusProperties { down = requester }
                            } ?: Modifier,
                        ).detailOnPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionUp &&
                                selectedTabRequester != null
                            ) {
                                selectedTabRequester.requestFocusSafely()
                            } else {
                                false
                            }
                        },
                contentPadding =
                    PaddingValues(
                        start = detailHorizontalInset() + DetailDimens.focusBorder,
                        top = DetailDimens.focusBorder,
                        end = detailHorizontalInset() + DetailDimens.rowGap,
                        bottom = DetailDimens.seasonEpisodeCardBottomPadding,
                    ),
                focusContainer = episodeFocus,
                focusFallback = { listOfNotNull(defaultEntryEpisodeId, firstEpisodeId) },
            ) { episode, focusModifier ->
                EpisodeCard(
                    session = session,
                    episode = episode,
                    requestInitialFocus =
                        !episodesFocusedOnce &&
                            !restoreExpired &&
                            episode.itemId == firstEpisodeId &&
                            defaultEntryEpisodeId == firstEpisodeId,
                    selected = episode.itemId == effectiveFocusedEpisodeId,
                    currentFocusedEpisodeId = currentFocusedEpisodeIdProvider,
                    focusModifier = focusModifier,
                    onInitialFocusConsumed = { onEpisodesFocusedOnce() },
                    onFocused = {
                        if (episode.itemId != defaultEntryEpisodeId) {
                            restoreExpired = true
                        }
                        onFocusedEpisode(episode.itemId)
                    },
                    onClick = { currentFocusedEpisodeId ->
                        onEpisodeSelected(episode, currentFocusedEpisodeId)
                    },
                )
            }
        }
    }
}

@Composable
internal fun EpisodeStripFrame(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.detailFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailShelfTitleGap),
    ) {
        DetailText(
            text = stringResource(Res.string.tv_episodes),
            modifier = Modifier.padding(horizontal = detailHorizontalInset()),
            style = DetailTitleStyle,
        )
        content()
    }
}

@Composable
private fun EpisodeCard(
    session: Session,
    episode: EpisodeUi,
    requestInitialFocus: Boolean,
    selected: Boolean,
    // A provider, not the id itself. The raw id was a parameter of every episode
    // card but is only consumed inside the click lambda, so each focus move — which
    // changes the id — recomposed every visible card to hand them a value none of
    // them render. Reading it inside the lambda keeps the identity read at the one
    // place that needs it, at the moment it is needed.
    currentFocusedEpisodeId: () -> String?,
    focusModifier: Modifier,
    onInitialFocusConsumed: () -> Unit,
    onFocused: () -> Unit,
    onClick: (String?) -> Unit,
) {
    val dpad = isDetailDpadMode()
    val requester = remember { FocusRequester() }

    if (dpad) {
        LaunchedEffect(requestInitialFocus, episode.itemId) {
            if (requestInitialFocus) {
                requester.requestFocusSafely()
                onInitialFocusConsumed()
            }
        }
    }

    AdaptiveEpisodeThumb(
        session = session,
        episode = episode,
        selected = selected,
        onClick = { onClick(currentFocusedEpisodeId()) },
        modifier =
            Modifier
                .detailFocusRequester(requester)
                .then(focusModifier),
        onFocused = onFocused,
    )
}

internal fun SeriesContentUi.episodeQueueFrom(playedId: String): List<String> =
    ((episodesState as? SeasonEpisodesUiState.Content)?.episodes)
        ?.dropWhile { episode -> episode.itemId != playedId }
        ?.map { episode -> episode.itemId }
        .orEmpty()

internal fun effectiveFocusedEpisodeId(
    focusedEpisodeId: String?,
    validEpisodeIds: Set<String>,
    defaultEpisodeId: String?,
): String? = focusedEpisodeId?.takeIf { itemId -> itemId in validEpisodeIds } ?: defaultEpisodeId

internal fun SeasonEpisodesUiState.focusedEpisodeOrDefault(
    focusedEpisodeId: String?,
    defaultEpisode: EpisodeUi?,
): EpisodeUi? =
    focusedEpisodeId
        ?.let { itemId ->
            (this as? SeasonEpisodesUiState.Content)?.episodes?.firstOrNull { episode ->
                episode.itemId == itemId
            }
        } ?: defaultEpisode

@Composable
internal fun EpisodeActionRow(
    episode: EpisodeUi,
    trackSelectionState: DetailTrackSelectionState,
    onPlayEpisode: (EpisodeUi, Int?, SubtitleSelectionIntent) -> Unit,
    onRestartEpisode: (EpisodeUi, Int?, SubtitleSelectionIntent) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMediaInfoClick: (EpisodeUi) -> Unit,
    infoRequester: FocusRequester,
    versionTrackRequester: FocusRequester,
    audioTrackRequester: FocusRequester,
    subtitleTrackRequester: FocusRequester,
    onTrackPickerClick: (DetailTrackPickerType) -> Unit,
    onSelectMediaVersion: (String) -> Unit,
    playRequester: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val playButtonRequester = remember { FocusRequester() }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // Entry lands on the group; the restorer returns focus to the
                // last-focused action button on re-entry, and falls back to Play on
                // the first entry (no saved child yet). Buttons never scroll off.
                .detailFocusRequester(playRequester)
                .then(downTarget?.let { target -> Modifier.detailFocusProperties { down = target } } ?: Modifier)
                .detailFocusRestorer(playButtonRequester)
                .detailFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailHeroTextGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DetailDimens.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryDetailPlayButton(
                label = detailTvPlayLabel(episode.playAction.label),
                timeLeftText = episode.timeLeftText,
                onClick = {
                    onPlayEpisode(
                        episode,
                        trackSelectionState.audioStreamIndexForPlay(episode.trackSelection),
                        trackSelectionState.subtitleSelectionForPlay(episode.trackSelection),
                    )
                },
                modifier = Modifier.detailFocusRequester(playButtonRequester),
            )
            episode.restartAction?.let {
                ExpandingActionButton(
                    label = stringResource(Res.string.tv_restart),
                    icon = DetailActionIcon.Restart,
                    onClick = {
                        onRestartEpisode(
                            episode,
                            trackSelectionState.audioStreamIndexForPlay(episode.trackSelection),
                            trackSelectionState.subtitleSelectionForPlay(episode.trackSelection),
                        )
                    },
                )
            }
            ExpandingActionButton(
                label =
                    if (episode.isWatched) {
                        stringResource(Res.string.tv_mark_unwatched)
                    } else {
                        stringResource(Res.string.tv_mark_watched)
                    },
                icon = if (episode.isWatched) DetailActionIcon.Watched else DetailActionIcon.Unwatched,
                active = episode.isWatched,
                enabled = !episode.watchedToggleInFlight,
                onClick = onToggleWatched,
            )
            ExpandingActionButton(
                label =
                    if (episode.isFavorite) {
                        stringResource(Res.string.tv_remove_favorite)
                    } else {
                        stringResource(Res.string.tv_add_favorite)
                    },
                icon = if (episode.isFavorite) DetailActionIcon.Favorite else DetailActionIcon.FavoriteOutline,
                active = episode.isFavorite,
                enabled = !episode.favoriteToggleInFlight,
                onClick = onToggleFavorite,
            )
            if (episode.mediaInfo?.hasAnything == true) {
                ExpandingActionButton(
                    label = stringResource(Res.string.media_info_action),
                    icon = DetailActionIcon.Info,
                    onClick = { onMediaInfoClick(episode) },
                    modifier = Modifier.detailFocusRequester(infoRequester),
                    contentDescription = stringResource(Res.string.media_info_open),
                )
            }
            DpadTrackSelectionButtons(
                trackSelection = episode.trackSelection,
                selectionState = trackSelectionState,
                versions = episode.versions,
                selectedMediaSourceId = episode.selectedMediaSourceId,
                versionRequester = versionTrackRequester,
                audioRequester = audioTrackRequester,
                subtitleRequester = subtitleTrackRequester,
                onOpenPicker = onTrackPickerClick,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (episode.streamBadges.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DetailDimens.detailBadgeGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    episode.streamBadges.forEach { badge ->
                        InfoChip(text = badge)
                    }
                }
            }
        }
        DetailTrackSelectionControls(
            trackSelection = episode.trackSelection,
            selectionState = trackSelectionState,
            versions = episode.versions,
            selectedMediaSourceId = episode.selectedMediaSourceId,
            onSelectMediaVersion = onSelectMediaVersion,
        )
    }
}

@Composable
internal fun AdaptiveSeriesError(
    retryable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalJellyfinPalette.current.gradientBottom)
                .padding(
                    horizontal = detailHorizontalInset(),
                    vertical = DetailDimens.overscanVertical,
                ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DetailText(
            text = stringResource(Res.string.tv_details_error),
            color = LocalJellyfinPalette.current.error,
        )
        if (retryable) {
            RetryButton(
                text = stringResource(Res.string.tv_retry),
                onClick = onRetry,
                modifier = Modifier.width(DetailDimens.detailBackButtonWidth),
            )
        }
    }
}

@Composable
private fun RetryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier,
        contentDescription = text,
        focusedScale = 1.05f,
        backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
        focusedBackgroundColor = LocalJellyfinPalette.current.cyan,
        contentPadding =
            PaddingValues(
                horizontal = DetailDimens.buttonHorizontalPadding,
                vertical = DetailDimens.buttonVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
        shape = RoundedCornerShape(percent = 50),
    ) { focused ->
        DetailText(
            text = text,
            style = DetailBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color =
                if (focused) {
                    LocalJellyfinPalette.current.onFocusedLight
                } else {
                    LocalJellyfinPalette.current.textPrimary
                },
            maxLines = 1,
        )
    }
}
