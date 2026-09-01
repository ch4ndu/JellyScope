// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.RELATED_GROUP_DISPLAY_LIMIT
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.AdaptiveCenteredSpinner
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.HeroDetailBackButton
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.focus.rememberDeadZoneCenterPivotBringIntoViewSpec
import com.jellyscope.ui.focus.rememberNoAutoScrollSpec
import com.jellyscope.ui.focus.rememberScrollIntoViewOnFocusModifier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AdaptiveDetailScreen(
    session: Session,
    itemId: String,
    onBack: () -> Unit,
    onPlay: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onPlayWithSubtitleIntent: ((String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit)? = null,
    onRelatedItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlayRelatedDirect: ((MediaCardUi) -> Unit)? = null,
    onOpenDownloads: () -> Unit = {},
    onPlayOffline: (DownloadRecord) -> Unit = {},
    viewModel: DetailViewModel =
        koinViewModel(
            parameters = { parametersOf(session, itemId) },
        ),
    ambientColorExtractor: AmbientColorExtractor = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val downloadEntryState by viewModel.downloadEntryState.collectAsStateWithLifecycle()
    var searchVisible by remember { mutableStateOf(false) }
    var originalDownloadVisible by remember { mutableStateOf(false) }
    var subtitleSearchReturnRequest by remember { mutableIntStateOf(0) }
    val contentDetail = (state as? DetailUiState.Content)?.detail
    val selectedSourceId = contentDetail?.selectedMediaSourceId
    var selectedLocalAssetId by remember(itemId, selectedSourceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(itemId, selectedSourceId, contentDetail?.trackSelection?.defaultLocalSubtitleAssetId) {
        selectedLocalAssetId = contentDetail?.trackSelection?.defaultLocalSubtitleAssetId
    }
    LaunchedEffect(itemId, selectedSourceId) {
        searchVisible = false
    }

    // Refresh the item (resume position / watched state) when the screen becomes
    // active again after the player. Mobile: the nav entry resumes (ON_RESUME);
    // TV: the route content is recomposed on return (LaunchedEffect). refresh()
    // is a no-op until the first load has produced Content, so neither path
    // double-loads on first entry. (#172)
    OnResumeEffect(viewModel::refresh)
    LaunchedEffect(viewModel) { viewModel.refresh() }

    Box(modifier = modifier.fillMaxSize()) {
        val detail = contentDetail
        val sourceId = detail?.playAction?.mediaSourceId
        val subtitleActions =
            sourceId?.let {
                DetailSubtitlePickerActions(
                    selectedLocalAssetId = selectedLocalAssetId,
                    onSelectSubtitle = { streamIndex ->
                        selectedLocalAssetId = null
                        viewModel.selectSubtitle(streamIndex)
                    },
                    onSelectLocalAsset = { assetId ->
                        selectedLocalAssetId = assetId
                        viewModel.selectLocalSubtitle(assetId)
                    },
                    onSearchSubtitles = { searchVisible = true },
                    onDeleteLocalAsset = { assetId ->
                        // Deleting some other asset leaves this one selected —
                        // only the selected asset's removal clears the highlight.
                        if (selectedLocalAssetId == assetId) {
                            selectedLocalAssetId = null
                        }
                        viewModel.deleteLocalSubtitle(assetId)
                    },
                    onRetryLocalAssetSync = viewModel::retryLocalSubtitleSync,
                    returnFocusRequest = subtitleSearchReturnRequest,
                )
            }
        when (val detailState = state) {
            DetailUiState.Loading -> AdaptiveCenteredSpinner()
            is DetailUiState.Error ->
                AdaptiveDetailError(
                    retryable = detailState.retryable,
                    onRetry = viewModel::retry,
                )
            is DetailUiState.Content ->
                AdaptiveDetailContent(
                    session = session,
                    detail = detailState.detail,
                    onBack = onBack,
                    onPlay = { playItemId, ticks, sourceId, audioIndex, subtitleSelection ->
                        val localId = selectedLocalAssetId
                        if (localId != null && onPlayWithSubtitleIntent != null) {
                            onPlayWithSubtitleIntent(
                                playItemId,
                                ticks,
                                sourceId,
                                audioIndex,
                                SubtitleSelectionIntent.LocalAsset(localId),
                            )
                        } else {
                            onPlay(playItemId, ticks, sourceId, audioIndex, subtitleSelection)
                        }
                    },
                    onToggleWatched = viewModel::toggleWatched,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onSelectMediaVersion = viewModel::selectMediaVersion,
                    subtitleActions = subtitleActions,
                    downloadEntryState = downloadEntryState.takeIf { viewModel.isDownloadAvailable },
                    downloadActionEnabled = !originalDownloadVisible,
                    downloadFixedAvailable = viewModel.isFixedDownloadAvailable,
                    onDownloadClick = {
                        when (val entry = downloadEntryState) {
                            DetailDownloadEntryState.Add -> {
                                viewModel.resetOriginalDownloadState()
                                originalDownloadVisible = true
                            }
                            is DetailDownloadEntryState.Manage -> onOpenDownloads()
                            is DetailDownloadEntryState.PlayOffline -> onPlayOffline(entry.record)
                        }
                    },
                    onRelatedItemSelected = onRelatedItemSelected,
                    onPersonSelected = onPersonSelected,
                    onPlayRelatedDirect = onPlayRelatedDirect,
                    ambientColorExtractor = ambientColorExtractor,
                )
        }
        if (originalDownloadVisible && detail != null) {
            OriginalDownloadDialog(
                detail = detail,
                initialLocalAssetId = selectedLocalAssetId,
                state = downloadState,
                fixedAvailable = viewModel.isFixedDownloadAvailable,
                onDismiss = {
                    originalDownloadVisible = false
                    viewModel.resetOriginalDownloadState()
                },
                onPreview = viewModel::previewOriginalDownload,
                onConfirm = viewModel::enqueueOriginalDownload,
                onPreviewFixed = viewModel::previewFixedDownload,
                onConfirmFixedBurnIn = viewModel::confirmFixedBurnIn,
                onCancelFixedBurnIn = viewModel::cancelFixedBurnIn,
                onConfirmFixed = viewModel::enqueueFixedDownload,
            )
        }
        if (detail != null && sourceId != null) {
            if (searchVisible) {
                OpenSubtitleSearchDialog(
                    request =
                        OpenSubtitleSearchRequest(
                            context = LocalSubtitleContext(session.serverId, session.userId, detail.itemId, sourceId),
                            title = detail.title,
                            year = detail.productionYear,
                            imdbId = detail.imdbId,
                            seasonNumber = detail.seasonNumber,
                            episodeNumber = detail.episodeNumber,
                            language = detail.subtitleSearchLanguage,
                            sourceReleaseBasename = detail.selectedSourceReleaseBasename,
                            seriesTitle = detail.seriesName,
                        ),
                    onInstalled = { asset ->
                        selectedLocalAssetId = asset.id
                    },
                    onDismiss = {
                        searchVisible = false
                        subtitleSearchReturnRequest += 1
                    },
                )
            }
        }
    }
}

@Composable
internal fun AdaptiveDetailContent(
    session: Session,
    detail: DetailUi,
    onBack: () -> Unit,
    onPlay: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectMediaVersion: (String) -> Unit = {},
    subtitleActions: DetailSubtitlePickerActions? = null,
    downloadEntryState: DetailDownloadEntryState? = null,
    downloadActionEnabled: Boolean = true,
    downloadFixedAvailable: Boolean = false,
    onDownloadClick: () -> Unit = {},
    onRelatedItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlayRelatedDirect: ((MediaCardUi) -> Unit)? = null,
    ambientColorExtractor: AmbientColorExtractor,
) {
    val dpad = isDetailDpadMode()
    val playRequester = remember { FocusRequester() }
    val mediaInfoRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val hasCast = detail.castAndCrew.isNotEmpty()
    val heroBringIntoViewModifier = rememberScrollIntoViewOnFocusModifier(listState, index = 0, enabled = dpad)
    val castBringIntoViewModifier = rememberScrollIntoViewOnFocusModifier(listState, index = 1, enabled = dpad)
    // One bring-into-view hook per possible related shelf (indices follow the
    // hero -> cast -> related-shelves order used in the D-pad layout below).
    val relatedBaseIndex = if (hasCast) 2 else 1
    val relatedBringIntoViewModifiers =
        List(RELATED_GROUP_DISPLAY_LIMIT) { shelf ->
            rememberScrollIntoViewOnFocusModifier(listState, index = relatedBaseIndex + shelf, enabled = dpad)
        }
    val noAutoScrollSpec = rememberNoAutoScrollSpec()
    val horizontalBringIntoViewSpec = rememberDeadZoneCenterPivotBringIntoViewSpec()
    var ambientColor by remember { mutableStateOf<Color?>(null) }
    var mediaInfoVisible by remember { mutableStateOf(false) }
    var restoreMediaInfoFocus by remember { mutableStateOf(false) }
    var trackPickerVisible by remember { mutableStateOf<DetailTrackPickerType?>(null) }
    var restoreTrackPickerFocus by remember { mutableStateOf<DetailTrackPickerType?>(null) }
    var openedVersionSourceId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mediaInfo = detail.mediaInfo?.takeIf { info -> info.hasAnything }
    val versionTrackRequester = remember { FocusRequester() }
    val audioTrackRequester = remember { FocusRequester() }
    val castFocus = rememberRouteDetailFocusContainer("detail/cast")
    // One focus container per possible related shelf so each remembers its own
    // last-focused card (and never orphans focus to another shelf on reload).
    val relatedFocuses =
        List(RELATED_GROUP_DISPLAY_LIMIT) { shelf ->
            rememberRouteDetailFocusContainer("detail/related:$shelf")
        }
    var relatedLoadingHadFocus by remember { mutableStateOf(false) }
    val subtitleTrackRequester = remember { FocusRequester() }
    val trackSelectionState =
        rememberDetailTrackSelectionState(
            itemId = detail.itemId,
            trackSelection = detail.trackSelection,
        )

    fun dismissTrackPicker() {
        val picker = trackPickerVisible ?: return
        restoreTrackPickerFocus = picker
        trackPickerVisible = null
    }

    fun openTrackPicker(picker: DetailTrackPickerType) {
        if (picker == DetailTrackPickerType.Version) {
            openedVersionSourceId = detail.selectedMediaSourceId
        }
        trackPickerVisible = picker
    }

    LaunchedEffect(trackPickerVisible, openedVersionSourceId, detail.versions, detail.selectedMediaSourceId) {
        if (trackPickerVisible == DetailTrackPickerType.Version &&
            shouldDismissVersionPicker(
                openedMediaSourceId = openedVersionSourceId,
                versions = detail.versions,
                selectedMediaSourceId = detail.selectedMediaSourceId,
            )
        ) {
            dismissTrackPicker()
        }
    }

    if (dpad) {
        LaunchedEffect(detail.itemId) {
            if (castFocus.restorePending() || relatedFocuses.any { focus -> focus.restorePending() }) {
                return@LaunchedEffect
            }
            playRequester.requestFocusSafely()
            listState.scrollToItem(0)
        }

        // The trailing loading spinner lives for the whole related-loading phase
        // (shelves stream in above it). When loading finishes and the spinner is
        // removed, if focus was parked on it, hand off to the last loaded shelf (else
        // back to Play). Without this the focused spinner node is removed and focus
        // would orphan.
        LaunchedEffect(detail.relatedLoading) {
            if (!detail.relatedLoading && relatedLoadingHadFocus) {
                relatedLoadingHadFocus = false
                delay(RELATED_HANDOFF_DELAY_MS)
                val lastShelf = detail.relatedGroups.lastIndex.coerceAtMost(relatedFocuses.lastIndex)
                val landed =
                    lastShelf >= 0 &&
                        relatedFocuses[lastShelf].entryRequester.requestFocusSafely()
                if (!landed) {
                    playRequester.requestFocusSafely()
                }
            }
        }

        LaunchedEffect(mediaInfoVisible) {
            if (!mediaInfoVisible && restoreMediaInfoFocus) {
                restoreMediaInfoFocus = false
                withFrameNanos { }
                val restoredInfo = mediaInfoRequester.requestFocusSafely()
                if (!restoredInfo) {
                    playRequester.requestFocusSafely()
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
                    playRequester.requestFocusSafely()
                }
            }
        }

        LaunchedEffect(subtitleActions?.returnFocusRequest) {
            if ((subtitleActions?.returnFocusRequest ?: 0) > 0) {
                withFrameNanos { }
                val restoredSubtitleButton = subtitleTrackRequester.requestFocusSafely()
                if (!restoredSubtitleButton) {
                    playRequester.requestFocusSafely()
                }
            }
        }
    }

    AmbientBackground(
        ambientColor = ambientColor,
        modifier =
            modifier
                .fillMaxSize(),
    ) {
        AmbientLayer(ambientColor = ambientColor)
        DetailBackdrop(
            session = session,
            detail = detail,
            onPosterLoaded = { image ->
                scope.launch {
                    ambientColor =
                        ambientColorExtractor.extract(
                            image,
                            ambientImageCacheKey(session, detail.itemId, detail.backdropUrl),
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
                // Tighter spacing between shelves (cast -> related and related ->
                // related). The hero -> cast gap keeps the full section gap via the
                // cast item's top padding below.
                verticalArrangement = Arrangement.spacedBy(DetailDimens.detailSectionGap / 2f),
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
                        DetailHero(session = session, detail = detail)
                        DetailActionRow(
                            detail = detail,
                            trackSelectionState = trackSelectionState,
                            playRequester = playRequester,
                            mediaInfoRequester = mediaInfoRequester,
                            onPlay = onPlay,
                            onToggleWatched = onToggleWatched,
                            onToggleFavorite = onToggleFavorite,
                            downloadEntryState = downloadEntryState,
                            downloadActionEnabled = downloadActionEnabled,
                            downloadFixedAvailable = downloadFixedAvailable,
                            onDownloadClick = onDownloadClick,
                            subtitleActions = subtitleActions,
                            onMediaInfoClick = { mediaInfoVisible = true },
                            versionTrackRequester = versionTrackRequester,
                            audioTrackRequester = audioTrackRequester,
                            subtitleTrackRequester = subtitleTrackRequester,
                            onTrackPickerClick = ::openTrackPicker,
                        )
                        DetailTrackSelectionControls(
                            trackSelection = detail.trackSelection,
                            selectionState = trackSelectionState,
                            versions = detail.versions,
                            selectedMediaSourceId = detail.selectedMediaSourceId,
                            onSelectMediaVersion = onSelectMediaVersion,
                            subtitleActions = subtitleActions,
                        )
                    }
                }
                if (!dpad && (detail.imdbUrl != null || detail.tmdbUrl != null)) {
                    item(key = "external-links") {
                        Box(modifier = Modifier.padding(horizontal = detailHorizontalInset())) {
                            ExternalLinksRow(detail)
                        }
                    }
                }
                if (detail.castAndCrew.isNotEmpty()) {
                    item(key = "cast") {
                        DetailCastAndCrewShelf(
                            session = session,
                            people = detail.castAndCrew,
                            horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                            onPersonSelected = onPersonSelected,
                            castFocus = castFocus,
                            // Restore the full hero -> cast gap (the LazyColumn now
                            // uses half spacing for the tighter shelf rows).
                            modifier =
                                Modifier
                                    .padding(top = DetailDimens.detailSectionGap / 2f)
                                    .then(castBringIntoViewModifier),
                        )
                    }
                }
                val shownRelatedGroups = detail.relatedGroups.take(RELATED_GROUP_DISPLAY_LIMIT)
                shownRelatedGroups.forEachIndexed { shelf, group ->
                    if (group.items.isNotEmpty()) {
                        item(key = "related-${group.kind}-${group.label.orEmpty()}") {
                            AdaptiveRelatedShelf(
                                session = session,
                                title = relatedGroupTitle(group.kind, group.label),
                                items = group.items,
                                horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
                                onRelatedItemSelected = onRelatedItemSelected,
                                onPlayRelatedDirect = onPlayRelatedDirect,
                                relatedFocus = relatedFocuses[shelf],
                                modifier = relatedBringIntoViewModifiers[shelf],
                            )
                        }
                    }
                }
                // Trailing loading indicator: visible while ANY related shelf is still
                // being fetched, after whatever has loaded so far.
                if (detail.relatedLoading) {
                    item(key = "related-loading") {
                        AdaptiveRelatedLoadingShelf(
                            showTitle = shownRelatedGroups.isEmpty(),
                            onFocusChanged = { hasFocus -> relatedLoadingHadFocus = hasFocus },
                            modifier =
                                rememberScrollIntoViewOnFocusModifier(
                                    listState,
                                    index = relatedBaseIndex + shownRelatedGroups.size,
                                    enabled = dpad,
                                ),
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
        if (mediaInfoVisible && mediaInfo != null) {
            AdaptiveMediaInfoDialog(
                title = detail.title,
                mediaInfo = mediaInfo,
                onDismiss = {
                    restoreMediaInfoFocus = true
                    mediaInfoVisible = false
                },
            )
        }
        trackPickerVisible?.let { picker ->
            if (dpad) {
                DpadTrackSelectionPickerOverlay(
                    picker = picker,
                    trackSelection = detail.trackSelection,
                    selectionState = trackSelectionState,
                    versions = detail.versions,
                    selectedMediaSourceId = detail.selectedMediaSourceId,
                    onSelectMediaVersion = onSelectMediaVersion,
                    subtitleActions = subtitleActions,
                    onDismiss = ::dismissTrackPicker,
                )
            }
        }
    }
}

@Composable
internal fun detailHorizontalInset(): Dp =
    if (isDetailDpadMode()) {
        DetailDimens.overscanHorizontal
    } else {
        adaptiveHorizontalContentPadding().start
    }

@Composable
internal fun detailShelfPadding(): PaddingValues {
    val horizontalPadding = adaptiveHorizontalContentPadding()
    return if (isDetailDpadMode()) {
        PaddingValues(
            start = DetailDimens.overscanHorizontal + DetailDimens.focusBorder,
            top = DetailDimens.focusBorder,
            end = DetailDimens.overscanHorizontal + DetailDimens.rowGap,
            bottom = DetailDimens.detailShelfBottomPadding,
        )
    } else {
        PaddingValues(
            start = horizontalPadding.start,
            top = DetailDimens.focusBorder,
            end = horizontalPadding.end,
            bottom = DetailDimens.detailShelfBottomPadding,
        )
    }
}

// Small settle delay before handing focus off the related loading spinner to the
// real shelves — enough for them to compose and attach their entry requester.
private const val RELATED_HANDOFF_DELAY_MS = 48L
