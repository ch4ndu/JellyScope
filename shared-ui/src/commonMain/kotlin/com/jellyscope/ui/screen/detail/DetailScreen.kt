// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
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
fun DetailScreen(
    session: Session,
    itemId: String,
    onBack: () -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onSettingsClick: () -> Unit,
    onPersonSelected: (String) -> Unit = {},
    onPlayWithSubtitleIntent: ((String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel =
        koinViewModel(
            parameters = { parametersOf(session, itemId) },
        ),
    ambientColorExtractor: AmbientColorExtractor = koinInject(),
    onOpenDownloads: () -> Unit = {},
    onPlayOffline: (DownloadRecord) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val downloadEntryState by viewModel.downloadEntryState.collectAsStateWithLifecycle()
    var searchVisible by remember { mutableStateOf(false) }
    var originalDownloadVisible by remember { mutableStateOf(false) }
    var subtitleSearchReturnRequest by remember { mutableIntStateOf(0) }
    val detail = (state as? DetailUiState.Content)?.detail
    val selectedSourceId = detail?.selectedMediaSourceId
    val subtitleSelectionOwner =
        remember(session.serverId, session.userId, itemId, selectedSourceId) {
            DetailSubtitleSelectionOwner()
        }
    var subtitleSelectionRevision by remember(subtitleSelectionOwner) { mutableIntStateOf(0) }
    val subtitleSelectionToken =
        remember(subtitleSelectionOwner, subtitleSelectionRevision) {
            DetailSubtitleSelectionToken(subtitleSelectionOwner, subtitleSelectionRevision)
        }
    var installedAssetId by remember(itemId, selectedSourceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(itemId, selectedSourceId, detail?.trackSelection?.defaultLocalSubtitleAssetId) {
        installedAssetId = detail?.trackSelection?.defaultLocalSubtitleAssetId
    }
    LaunchedEffect(itemId, selectedSourceId) {
        searchVisible = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        val sourceId = detail?.playAction?.mediaSourceId
        val subtitleActions =
            sourceId?.let {
                DetailSubtitlePickerActions(
                    selectedLocalAssetId = installedAssetId,
                    onSelectSubtitle = { streamIndex ->
                        subtitleSelectionRevision += 1
                        installedAssetId = null
                        viewModel.selectSubtitle(streamIndex)
                    },
                    onSelectLocalAsset = { assetId ->
                        subtitleSelectionRevision += 1
                        installedAssetId = assetId
                        viewModel.selectLocalSubtitle(assetId)
                    },
                    onSearchSubtitles = { searchVisible = true },
                    onDeleteLocalAsset = { assetId ->
                        // Deleting some other asset leaves this one selected —
                        // only the selected asset's removal clears the highlight.
                        if (installedAssetId == assetId) {
                            subtitleSelectionRevision += 1
                            installedAssetId = null
                        }
                        viewModel.deleteLocalSubtitle(assetId)
                    },
                    onRetryLocalAssetSync = viewModel::retryLocalSubtitleSync,
                    returnFocusRequest = subtitleSearchReturnRequest,
                )
            }
        DetailContent(
            session = session,
            state = state,
            onBack = onBack,
            onRetry = viewModel::retry,
            onPlayClick = { playItemId, ticks, sourceId, audioIndex, subtitleSelection ->
                val localId = installedAssetId
                if (localId != null && onPlayWithSubtitleIntent != null) {
                    onPlayWithSubtitleIntent(playItemId, ticks, sourceId, audioIndex, SubtitleSelectionIntent.LocalAsset(localId))
                } else {
                    onPlayClick(playItemId, ticks, sourceId, audioIndex, subtitleSelection)
                }
            },
            onItemSelected = onItemSelected,
            onPersonSelected = onPersonSelected,
            onToggleWatched = viewModel::toggleWatched,
            onToggleFavorite = viewModel::toggleFavorite,
            onSelectMediaVersion = viewModel::selectMediaVersion,
            subtitleActions = subtitleActions,
            downloadEntryState = downloadEntryState.takeIf { detail != null && viewModel.isDownloadAvailable },
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
            ambientColorExtractor = ambientColorExtractor,
        )
        if (originalDownloadVisible && detail != null) {
            OriginalDownloadDialog(
                detail = detail,
                initialLocalAssetId = installedAssetId,
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
                    selectionToken = subtitleSelectionToken,
                    onInstalled = { asset ->
                        installedAssetId = asset.id
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
fun DetailContent(
    session: Session,
    state: DetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlayClick: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPersonSelected: (String) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectMediaVersion: (String) -> Unit = {},
    subtitleActions: DetailSubtitlePickerActions? = null,
    downloadEntryState: DetailDownloadEntryState? = null,
    downloadActionEnabled: Boolean = true,
    downloadFixedAvailable: Boolean = false,
    onDownloadClick: () -> Unit = {},
    ambientColorExtractor: AmbientColorExtractor,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DetailUiState.Loading ->
            DetailLoading(
                onBack = onBack,
                modifier = modifier,
            )
        is DetailUiState.Error ->
            DetailError(
                retryable = state.retryable,
                onBack = onBack,
                onRetry = onRetry,
                modifier = modifier,
            )
        is DetailUiState.Content ->
            DetailBody(
                session = session,
                detail = state.detail,
                onBack = onBack,
                onPlayClick = onPlayClick,
                onItemSelected = onItemSelected,
                onPersonSelected = onPersonSelected,
                onToggleWatched = onToggleWatched,
                onToggleFavorite = onToggleFavorite,
                onSelectMediaVersion = onSelectMediaVersion,
                subtitleActions = subtitleActions,
                downloadEntryState = downloadEntryState,
                downloadActionEnabled = downloadActionEnabled,
                downloadFixedAvailable = downloadFixedAvailable,
                onDownloadClick = onDownloadClick,
                ambientColorExtractor = ambientColorExtractor,
                modifier = modifier,
            )
    }
}

@Composable
private fun DetailBody(
    session: Session,
    detail: DetailUi,
    onBack: () -> Unit,
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
    ambientColorExtractor: AmbientColorExtractor,
    modifier: Modifier = Modifier,
) {
    val backContentDescription = stringResource(Res.string.detail_back)
    var mediaInfoVisible by remember { mutableStateOf(false) }
    var ambientColor by remember(detail.itemId) { mutableStateOf<Color?>(null) }
    val scope = rememberCoroutineScope()

    if (LocalWindowWidthTier.current != WindowWidthTier.Compact) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDetailInteractionMode provides DetailInteractionMode.Touch) {
            AdaptiveDetailContent(
                session = session,
                detail = detail,
                onBack = onBack,
                onPlay = onPlayClick,
                onToggleWatched = onToggleWatched,
                onToggleFavorite = onToggleFavorite,
                onSelectMediaVersion = onSelectMediaVersion,
                subtitleActions = subtitleActions,
                downloadEntryState = downloadEntryState,
                downloadActionEnabled = downloadActionEnabled,
                downloadFixedAvailable = downloadFixedAvailable,
                onDownloadClick = onDownloadClick,
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
            DetailBodyCompact(
                session = session,
                detail = detail,
                onPlayClick = onPlayClick,
                onItemSelected = onItemSelected,
                onPersonSelected = onPersonSelected,
                onToggleWatched = onToggleWatched,
                onToggleFavorite = onToggleFavorite,
                onSelectMediaVersion = onSelectMediaVersion,
                subtitleActions = subtitleActions,
                downloadEntryState = downloadEntryState,
                downloadActionEnabled = downloadActionEnabled,
                downloadFixedAvailable = downloadFixedAvailable,
                onDownloadClick = onDownloadClick,
                onMediaInfoClick = { mediaInfoVisible = true },
                onBackdropLoaded = { image ->
                    scope.launch {
                        ambientColor =
                            ambientColorExtractor.extract(
                                image,
                                ambientImageCacheKey(session, detail.itemId, detail.backdropUrl),
                            )
                    }
                },
            )
            HeroDetailBackButton(
                contentDescription = backContentDescription,
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopStart),
            )
            val mediaInfo = detail.mediaInfo
            if (mediaInfoVisible && mediaInfo != null && mediaInfo.hasAnything) {
                MediaInfoSheet(
                    title = detail.title,
                    mediaInfo = mediaInfo,
                    onDismiss = { mediaInfoVisible = false },
                )
            }
        }
    }
}
