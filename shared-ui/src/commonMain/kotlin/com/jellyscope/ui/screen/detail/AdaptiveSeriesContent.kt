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
import androidx.compose.foundation.lazy.items
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
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.HeroDetailBackButton
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.focus.rememberDeadZoneCenterPivotBringIntoViewSpec
import com.jellyscope.ui.focus.rememberNoAutoScrollSpec
import com.jellyscope.ui.focus.rememberScrollIntoViewOnFocusModifier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import com.jellyscope.ui.generated.resources.tv_related
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AdaptiveSeriesContent(
    session: Session,
    content: SeriesContentUi,
    onBack: () -> Unit,
    onPlay: (String, Long, String?, Int?, SubtitleSelectionIntent, List<String>) -> Unit,
    onSeasonSelected: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectEpisodeMediaVersion: (String, String) -> Unit = { _, _ -> },
    onRelatedItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlayRelatedDirect: ((MediaCardUi) -> Unit)? = null,
    ambientColorExtractor: AmbientColorExtractor,
) {
    val dpad = isDetailDpadMode()
    val playRequester = remember { FocusRequester() }
    val castFocus = rememberRouteDetailFocusContainer("series/cast")
    val relatedFocus = rememberRouteDetailFocusContainer("series/related")
    val seasonsFocus = rememberRouteDetailFocusContainer("series/seasons")
    val listState = rememberLazyListState()
    val hasCast = content.series.castAndCrew.isNotEmpty()
    val heroBringIntoViewModifier = rememberScrollIntoViewOnFocusModifier(listState, index = 0, enabled = dpad)
    val seasonsBringIntoViewModifier = rememberScrollIntoViewOnFocusModifier(listState, index = 1, enabled = dpad)
    val castBringIntoViewModifier = rememberScrollIntoViewOnFocusModifier(listState, index = 2, enabled = dpad)
    val relatedBringIntoViewModifier =
        rememberScrollIntoViewOnFocusModifier(listState, index = if (hasCast) 3 else 2, enabled = dpad)
    val noAutoScrollSpec = rememberNoAutoScrollSpec()
    val horizontalBringIntoViewSpec = rememberDeadZoneCenterPivotBringIntoViewSpec()
    var ambientColor by remember { mutableStateOf<Color?>(null) }
    var trackPickerVisible by remember { mutableStateOf<DetailTrackPickerType?>(null) }
    var restoreTrackPickerFocus by remember { mutableStateOf<DetailTrackPickerType?>(null) }
    var openedVersionSourceId by remember { mutableStateOf<String?>(null) }
    var routeEntryFocusHandled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val seriesPlayEpisode = content.seriesPlayEpisode
    val versionTrackRequester = remember { FocusRequester() }
    val audioTrackRequester = remember { FocusRequester() }
    val subtitleTrackRequester = remember { FocusRequester() }
    val trackSelectionState =
        seriesPlayEpisode?.let { episode ->
            rememberDetailTrackSelectionState(
                itemId = episode.itemId,
                trackSelection = episode.trackSelection,
            )
        }

    fun dismissTrackPicker() {
        val picker = trackPickerVisible ?: return
        restoreTrackPickerFocus = picker
        trackPickerVisible = null
    }

    fun openTrackPicker(picker: DetailTrackPickerType) {
        if (picker == DetailTrackPickerType.Version) {
            openedVersionSourceId = seriesPlayEpisode?.selectedMediaSourceId
        }
        trackPickerVisible = picker
    }

    LaunchedEffect(
        trackPickerVisible,
        openedVersionSourceId,
        seriesPlayEpisode?.versions,
        seriesPlayEpisode?.selectedMediaSourceId,
    ) {
        val episode = seriesPlayEpisode
        if (trackPickerVisible == DetailTrackPickerType.Version &&
            (
                episode == null ||
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
        LaunchedEffect(seriesPlayEpisode?.itemId) {
            if (routeEntryFocusHandled) {
                return@LaunchedEffect
            }
            if (castFocus.restorePending() || relatedFocus.restorePending() || seasonsFocus.restorePending()) {
                routeEntryFocusHandled = true
                return@LaunchedEffect
            }
            if (seriesPlayEpisode != null) {
                playRequester.requestFocusSafely()
                listState.scrollToItem(0)
                routeEntryFocusHandled = true
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
                    playRequester.requestFocusSafely()
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
                item(key = "hero-actions") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = detailHorizontalInset())
                                .then(heroBringIntoViewModifier),
                        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailSectionGap),
                    ) {
                        SeriesHero(series = content.series)
                        SeriesActionRow(
                            series = content.series,
                            episode = seriesPlayEpisode,
                            trackSelectionState = trackSelectionState,
                            playRequester = playRequester,
                            onPlay = { itemId, startTicks, mediaSourceId, audioStreamIndex, subtitleStreamIndex ->
                                onPlay(
                                    itemId,
                                    startTicks,
                                    mediaSourceId,
                                    audioStreamIndex,
                                    subtitleStreamIndex,
                                    content.episodeQueueFrom(itemId),
                                )
                            },
                            onToggleFavorite = onToggleFavorite,
                            versionTrackRequester = versionTrackRequester,
                            audioTrackRequester = audioTrackRequester,
                            subtitleTrackRequester = subtitleTrackRequester,
                            onTrackPickerClick = ::openTrackPicker,
                        )
                        if (seriesPlayEpisode != null && trackSelectionState != null) {
                            DetailTrackSelectionControls(
                                trackSelection = seriesPlayEpisode.trackSelection,
                                selectionState = trackSelectionState,
                                versions = seriesPlayEpisode.versions,
                                selectedMediaSourceId = seriesPlayEpisode.selectedMediaSourceId,
                                onSelectMediaVersion = { mediaSourceId ->
                                    onSelectEpisodeMediaVersion(seriesPlayEpisode.itemId, mediaSourceId)
                                },
                                showStaticAudioText = false,
                            )
                        }
                    }
                }
                item(key = "seasons") {
                    SeasonsShelf(
                        session = session,
                        seasons = content.seasons,
                        selectedSeasonId = content.selectedSeasonId,
                        horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                        onSeasonSelected = onSeasonSelected,
                        seasonsFocus = seasonsFocus,
                        modifier = seasonsBringIntoViewModifier,
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
                            modifier = castBringIntoViewModifier,
                        )
                    }
                }
                if (content.related.isNotEmpty()) {
                    item(key = "related") {
                        AdaptiveRelatedShelf(
                            session = session,
                            title = stringResource(Res.string.tv_related),
                            items = content.related,
                            horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                            onRelatedItemSelected = onRelatedItemSelected,
                            onPlayRelatedDirect = onPlayRelatedDirect,
                            relatedFocus = relatedFocus,
                            modifier = relatedBringIntoViewModifier,
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
        val picker = trackPickerVisible
        if (dpad && picker != null && seriesPlayEpisode != null && trackSelectionState != null) {
            DpadTrackSelectionPickerOverlay(
                picker = picker,
                trackSelection = seriesPlayEpisode.trackSelection,
                selectionState = trackSelectionState,
                versions = seriesPlayEpisode.versions,
                selectedMediaSourceId = seriesPlayEpisode.selectedMediaSourceId,
                onSelectMediaVersion = { mediaSourceId ->
                    onSelectEpisodeMediaVersion(seriesPlayEpisode.itemId, mediaSourceId)
                },
                onDismiss = ::dismissTrackPicker,
            )
        }
    }
}
