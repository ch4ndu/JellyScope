// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.HeroDetailBackButton
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.focus.rememberDeadZoneCenterPivotBringIntoViewSpec
import com.jellyscope.ui.focus.rememberNoAutoScrollSpec
import com.jellyscope.ui.focus.rememberScrollIntoViewOnFocusModifier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AdaptiveSeasonContent(
    session: Session,
    content: SeriesContentUi,
    focusedEpisodeId: StateFlow<String?>,
    onBack: () -> Unit,
    onPlayEpisode: (EpisodeUi, Int?, SubtitleSelectionIntent) -> Unit,
    onRestartEpisode: (EpisodeUi, Int?, SubtitleSelectionIntent) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSeasonSelected: (String) -> Unit,
    onFocusedEpisode: (String) -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit,
    onRetryEpisodes: () -> Unit,
    onPersonSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    ambientColorExtractor: AmbientColorExtractor,
) {
    val dpad = isDetailDpadMode()
    var ambientColor by remember { mutableStateOf<Color?>(null) }
    var infoEpisode by remember { mutableStateOf<EpisodeUi?>(null) }
    var restoreInfoEpisodeFocus by remember { mutableStateOf(false) }
    var trackPickerVisible by remember { mutableStateOf<DetailTrackPickerType?>(null) }
    var trackPickerEpisode by remember { mutableStateOf<EpisodeUi?>(null) }
    var restoreTrackPickerFocus by remember { mutableStateOf<DetailTrackPickerType?>(null) }
    var openedVersionSourceId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val defaultFocusedEpisode = content.focusedEpisode
    val currentFocusedEpisodeId by focusedEpisodeId.collectAsStateWithLifecycle()
    val actionEpisode =
        content.episodesState.focusedEpisodeOrDefault(currentFocusedEpisodeId, defaultFocusedEpisode)
    val episodeTrackSelectionState =
        if (actionEpisode != null) {
            rememberDetailTrackSelectionState(
                itemId = actionEpisode.itemId,
                trackSelection = actionEpisode.trackSelection,
            )
        } else {
            null
        }
    val hasEpisodeActions =
        defaultFocusedEpisode != null ||
            (content.episodesState as? SeasonEpisodesUiState.Content)?.episodes?.isNotEmpty() == true
    val seasonHeaderBringIntoViewModifier = rememberScrollIntoViewOnFocusModifier(listState, index = 0, enabled = dpad)
    val episodeActionsBringIntoViewModifier =
        rememberScrollIntoViewOnFocusModifier(listState, index = 1, enabled = dpad)
    val castBringIntoViewModifier = rememberScrollIntoViewOnFocusModifier(listState, index = 2, enabled = dpad)
    val noAutoScrollSpec = rememberNoAutoScrollSpec()
    val horizontalBringIntoViewSpec = rememberDeadZoneCenterPivotBringIntoViewSpec()
    val selectedTabRequester = remember { FocusRequester() }
    val episodeFocus = rememberRouteDetailFocusContainer(seasonScopeKey(content.selectedSeasonId, "episodes"))
    // Alias: the tabs' DOWN handler and the overlay-restore fallbacks target this
    // requester, which is the episode container's entry — so focus routes INTO the
    // container (its onEnter picks the remembered episode, else the first).
    val episodeEntryRequester = episodeFocus.entryRequester
    // One-shot: focus the episode ribbon on FIRST load only. Not re-armed on season
    // switch (parent-scoped), so switching seasons keeps focus on the season tab.
    var episodesFocusedOnce by remember { mutableStateOf(false) }
    val episodeActionsRequester = remember { FocusRequester() }
    val versionTrackRequester = remember { FocusRequester() }
    val audioTrackRequester = remember { FocusRequester() }
    val subtitleTrackRequester = remember { FocusRequester() }
    // Per season, like the episode strip container above: a fresh cast container
    // each season so focus never restores to another season's cast index.
    val castFocus = rememberRouteDetailFocusContainer(seasonScopeKey(content.selectedSeasonId, "cast"))
    // Alias so the actions' DOWN target routes into the cast container's entry.
    val castShelfRequester = castFocus.entryRequester
    val episodeInfoRequester = remember { FocusRequester() }
    val selectedSeasonTabRequester =
        selectedTabRequester.takeIf { content.seasons.any { season -> season.id == content.selectedSeasonId } }

    fun dismissTrackPicker() {
        val picker = trackPickerVisible ?: return
        restoreTrackPickerFocus = picker
        trackPickerVisible = null
        trackPickerEpisode = null
    }

    LaunchedEffect(
        trackPickerVisible,
        openedVersionSourceId,
        actionEpisode?.versions,
        actionEpisode?.selectedMediaSourceId,
    ) {
        val episode = actionEpisode
        if (trackPickerVisible == DetailTrackPickerType.Version &&
            (
                episode == null ||
                    trackPickerEpisode?.itemId != episode.itemId ||
                    shouldDismissVersionPicker(
                        openedMediaSourceId = openedVersionSourceId,
                        versions = episode.versions,
                        selectedMediaSourceId = episode.selectedMediaSourceId,
                    )
            )
        ) {
            dismissTrackPicker()
        }
    }

    if (dpad) {
        LaunchedEffect(content.selectedSeasonId) {
            if (episodeFocus.restorePending() || castFocus.restorePending()) {
                episodesFocusedOnce = true
            }
            trackPickerVisible = null
            trackPickerEpisode = null
            listState.scrollToItem(0)
        }

        LaunchedEffect(infoEpisode) {
            if (infoEpisode == null && restoreInfoEpisodeFocus) {
                restoreInfoEpisodeFocus = false
                withFrameNanos { }
                val restoredInfo = episodeInfoRequester.requestFocusSafely()
                if (!restoredInfo) {
                    episodeEntryRequester.requestFocusSafely()
                }
            }
        }

        LaunchedEffect(trackPickerVisible) {
            if (trackPickerVisible == null) {
                val picker = restoreTrackPickerFocus ?: return@LaunchedEffect
                restoreTrackPickerFocus = null
                withFrameNanos { }
                val requester =
                    when (picker) {
                        DetailTrackPickerType.Version -> versionTrackRequester
                        DetailTrackPickerType.Audio -> audioTrackRequester
                        DetailTrackPickerType.Subtitles -> subtitleTrackRequester
                    }
                val restoredTrackButton = requester.requestFocusSafely()
                if (!restoredTrackButton) {
                    episodeActionsRequester.requestFocusSafely()
                }
            }
        }
    }

    AmbientBackground(
        ambientColor = ambientColor,
        modifier = modifier.fillMaxSize(),
    ) {
        AmbientLayer(ambientColor = ambientColor)
        SeriesBackdrop(
            session = session,
            itemId = content.series.itemId,
            imageUrl = content.series.backdropUrl ?: content.series.posterUrl,
            onPosterLoaded = { image ->
                scope.launch {
                    ambientColor =
                        ambientColorExtractor.extract(
                            image,
                            ambientImageCacheKey(session, content.series.itemId, content.series.backdropUrl),
                        )
                }
            },
            modifier = Modifier.align(Alignment.TopEnd),
        )
        DetailBringIntoViewProvider(noAutoScrollSpec) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .then(
                            if (dpad) {
                                Modifier
                            } else {
                                Modifier.desktopScrollInput(listState, DesktopScrollOrientation.Vertical)
                            },
                        ),
                verticalArrangement = Arrangement.spacedBy(DetailDimens.detailSectionGap),
                contentPadding =
                    PaddingValues(
                        top = DetailDimens.overscanVertical + DetailDimens.heroTopInset,
                        bottom = DetailDimens.overscanVertical + DetailDimens.rowGap,
                    ),
            ) {
                item(key = "season-header") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (dpad) {
                                        Modifier
                                    } else {
                                        Modifier.padding(top = detailOverlayBackButtonTopPadding())
                                    },
                                ).then(seasonHeaderBringIntoViewModifier),
                        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailSectionGap),
                    ) {
                        SeasonTabs(
                            seasons = content.seasons,
                            selectedSeasonId = content.selectedSeasonId,
                            selectedTabRequester = selectedSeasonTabRequester,
                            episodeEntryRequester = episodeEntryRequester,
                            horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                            onSeasonSelected = onSeasonSelected,
                        )
                        FocusedEpisodeMetadata(
                            series = content.series,
                            episodesState = content.episodesState,
                            focusedEpisodeId = focusedEpisodeId,
                            defaultEpisode = defaultFocusedEpisode,
                        )
                        EpisodeStrip(
                            session = session,
                            episodesState = content.episodesState,
                            focusedEpisodeId = focusedEpisodeId,
                            defaultFocusedEpisodeId = defaultFocusedEpisode?.itemId,
                            selectedSeasonId = content.selectedSeasonId,
                            episodesFocusedOnce = episodesFocusedOnce,
                            onEpisodesFocusedOnce = { episodesFocusedOnce = true },
                            selectedTabRequester = selectedSeasonTabRequester,
                            episodeFocus = episodeFocus,
                            episodeActionsRequester = episodeActionsRequester.takeIf { hasEpisodeActions },
                            horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                            onFocusedEpisode = onFocusedEpisode,
                            onEpisodeSelected = { episode, currentFocusedEpisodeId ->
                                if (!dpad && currentFocusedEpisodeId != episode.itemId) {
                                    onFocusedEpisode(episode.itemId)
                                } else {
                                    onPlayEpisode(
                                        episode,
                                        null,
                                        episode.trackSelection.initialSubtitleSelection,
                                    )
                                }
                            },
                            onRetryEpisodes = onRetryEpisodes,
                        )
                    }
                }
                item(key = "episode-actions") {
                    FocusedEpisodeActionRow(
                        selectedSeasonId = content.selectedSeasonId,
                        episode = actionEpisode,
                        trackSelectionState = episodeTrackSelectionState,
                        onPlayEpisode = onPlayEpisode,
                        onRestartEpisode = onRestartEpisode,
                        onToggleWatched = onToggleWatched,
                        onToggleFavorite = onToggleFavorite,
                        onMediaInfoClick = { infoEpisode = it },
                        infoRequester = episodeInfoRequester,
                        playRequester = episodeActionsRequester,
                        versionTrackRequester = versionTrackRequester,
                        audioTrackRequester = audioTrackRequester,
                        subtitleTrackRequester = subtitleTrackRequester,
                        onTrackPickerClick = { picker, episode ->
                            trackPickerEpisode = episode
                            if (picker == DetailTrackPickerType.Version) {
                                openedVersionSourceId = episode.selectedMediaSourceId
                            }
                            trackPickerVisible = picker
                        },
                        onSelectMediaVersion = { mediaSourceId ->
                            actionEpisode?.let { episode ->
                                onSelectEpisodeMediaVersion(episode.itemId, mediaSourceId)
                            }
                        },
                        downTarget = castShelfRequester.takeIf { content.series.castAndCrew.isNotEmpty() },
                        modifier =
                            Modifier
                                .padding(horizontal = detailHorizontalInset())
                                .then(episodeActionsBringIntoViewModifier),
                    )
                }
                if (content.series.castAndCrew.isNotEmpty()) {
                    item(key = "cast") {
                        DetailCastAndCrewShelf(
                            session = session,
                            people = content.series.castAndCrew,
                            horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                            onPersonSelected = onPersonSelected,
                            castFocus = castFocus,
                            upTarget = episodeActionsRequester.takeIf { hasEpisodeActions },
                            modifier = castBringIntoViewModifier,
                        )
                    }
                }
            }
        }
        if (!dpad) {
            HeroDetailBackButton(
                contentDescription = stringResource(Res.string.detail_back),
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        val dialogEpisode = infoEpisode
        val mediaInfo = dialogEpisode?.mediaInfo
        if (dialogEpisode != null && mediaInfo != null && mediaInfo.hasAnything) {
            AdaptiveMediaInfoDialog(
                title = dialogEpisode.title,
                mediaInfo = mediaInfo,
                onDismiss = {
                    restoreInfoEpisodeFocus = true
                    infoEpisode = null
                },
            )
        }
        val picker = trackPickerVisible
        val pickerEpisode = actionEpisode?.takeIf { episode -> episode.itemId == trackPickerEpisode?.itemId }
        if (
            dpad &&
            picker != null &&
            pickerEpisode != null &&
            episodeTrackSelectionState != null
        ) {
            DpadTrackSelectionPickerOverlay(
                picker = picker,
                trackSelection = pickerEpisode.trackSelection,
                selectionState = episodeTrackSelectionState,
                versions = pickerEpisode.versions,
                selectedMediaSourceId = pickerEpisode.selectedMediaSourceId,
                onSelectMediaVersion = { mediaSourceId ->
                    onSelectEpisodeMediaVersion(pickerEpisode.itemId, mediaSourceId)
                },
                onDismiss = ::dismissTrackPicker,
            )
        }
    }
}

internal fun seasonScopeKey(
    seasonId: String?,
    section: String,
): String = "season/${seasonId ?: "unselected"}/$section"

@Composable
private fun FocusedEpisodeActionRow(
    selectedSeasonId: String?,
    episode: EpisodeUi?,
    trackSelectionState: DetailTrackSelectionState?,
    onPlayEpisode: (EpisodeUi, Int?, SubtitleSelectionIntent) -> Unit,
    onRestartEpisode: (EpisodeUi, Int?, SubtitleSelectionIntent) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMediaInfoClick: (EpisodeUi) -> Unit,
    infoRequester: FocusRequester,
    playRequester: FocusRequester,
    versionTrackRequester: FocusRequester,
    audioTrackRequester: FocusRequester,
    subtitleTrackRequester: FocusRequester,
    onTrackPickerClick: (DetailTrackPickerType, EpisodeUi) -> Unit,
    onSelectMediaVersion: (String) -> Unit,
    downTarget: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    if (episode == null || trackSelectionState == null) return

    key(selectedSeasonId) {
        EpisodeActionRow(
            episode = episode,
            trackSelectionState = trackSelectionState,
            onPlayEpisode = onPlayEpisode,
            onRestartEpisode = onRestartEpisode,
            onToggleWatched = onToggleWatched,
            onToggleFavorite = onToggleFavorite,
            onMediaInfoClick = onMediaInfoClick,
            infoRequester = infoRequester,
            playRequester = playRequester,
            versionTrackRequester = versionTrackRequester,
            audioTrackRequester = audioTrackRequester,
            subtitleTrackRequester = subtitleTrackRequester,
            onTrackPickerClick = { picker -> onTrackPickerClick(picker, episode) },
            onSelectMediaVersion = onSelectMediaVersion,
            downTarget = downTarget,
            modifier = modifier,
        )
    }
}
