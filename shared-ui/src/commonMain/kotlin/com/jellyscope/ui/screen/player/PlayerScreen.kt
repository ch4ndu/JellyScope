// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.platform.FullscreenToggleState
import com.jellyscope.ui.platform.LocalFullscreenToggle
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import kotlinx.coroutines.flow.collect
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PlayerScreen(
    session: Session,
    boundaryEpoch: Long,
    itemId: String,
    startPositionTicks: Long,
    mediaSourceId: String?,
    initialAudioStreamIndex: Int? = null,
    initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
    queue: List<String> = emptyList(),
    offlineDownloadId: DownloadId? = null,
    offlineRestartFromBeginning: Boolean = false,
    launchPolicy: PlayerLaunchPolicy = PlayerLaunchPolicy.Normal,
    onBack: () -> Unit,
    onOpenPlaybackSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    modifier: Modifier = Modifier,
    kidsLaunchRequest: KidsPlaybackLaunchRequest? = null,
    miniPlayerRailWidth: () -> Dp = { 0.dp },
    miniPlayerBottomPadding: Dp = 0.dp,
    onKidsExpandedChanged: (Boolean) -> Unit = {},
    viewModel: PlayerViewModel =
        koinViewModel(
            parameters = {
                parametersOf(
                    session,
                    itemId,
                    PlayerLaunchOptions(
                        startPositionTicks = startPositionTicks,
                        mediaSourceId = mediaSourceId,
                        initialAudioStreamIndex = initialAudioStreamIndex,
                        initialSubtitleSelection = initialSubtitleSelection,
                        queue = queue,
                        offlineDownloadId = offlineDownloadId,
                        offlineRestartFromBeginning = offlineRestartFromBeginning,
                        launchPolicy = launchPolicy,
                    ),
                )
            },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val softwareRecovery by viewModel.softwarePlaybackRecovery.collectAsStateWithLifecycle()
    val presentationState = if (softwareRecovery?.switching == true) PlayerUiState.Loading else state
    val kidsWatchViewModel: KidsWatchViewModel? =
        if (launchPolicy == PlayerLaunchPolicy.KidsSingleAsset) {
            koinViewModel(
                parameters = { parametersOf(session, boundaryEpoch, itemId, offlineDownloadId) },
            )
        } else {
            null
        }
    val kidsState =
        if (kidsWatchViewModel != null) {
            val state by kidsWatchViewModel.state.collectAsStateWithLifecycle()
            state
        } else {
            null
        }
    var isInPictureInPicture by remember { mutableStateOf(false) }
    var kidsFullscreen by remember(viewModel) { mutableStateOf(false) }
    var kidsMinimized by remember(viewModel) { mutableStateOf(false) }
    SideEffect { onKidsExpandedChanged(!kidsMinimized && !isInPictureInPicture) }
    // The constructor already launched the first request; only later identities are admitted.
    val firstKidsLaunchRequestId = remember(viewModel) { kidsLaunchRequest?.requestId }
    var playerSourceBounds by remember { mutableStateOf<Rect?>(null) }
    val platformCapabilities = LocalPlatformCapabilities.current
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnOpenPlaybackSettings by rememberUpdatedState(onOpenPlaybackSettings)
    var backDispatched by remember(viewModel) { mutableStateOf(false) }

    fun stopAndBack() {
        if (backDispatched) {
            return
        }
        backDispatched = true
        viewModel.stop()
        currentOnBack()
    }

    fun stopAndOpenPlaybackSettings() {
        if (backDispatched) {
            return
        }
        backDispatched = true
        viewModel.stop()
        currentOnOpenPlaybackSettings()
    }

    fun stopAndOpenDownloads() {
        if (backDispatched) {
            return
        }
        backDispatched = true
        viewModel.stop()
        onOpenDownloads()
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.dispose()
        }
    }

    // Use the activity lifecycle; a NavHost entry also stops while being popped.

    LaunchedEffect(viewModel) {
        viewModel.playbackEnded.collect {
            stopAndBack()
        }
    }

    val onPlaybackItemId = remember(viewModel) { { (viewModel.state.value as? PlayerUiState.Content)?.playbackItemId } }
    val onBackAction = remember { { stopAndBack() } }
    val onPlaybackActionCallback =
        remember(viewModel) {
            { action: PlaybackAction ->
                when (action) {
                    PlaybackAction.OpenPlaybackSettings -> stopAndOpenPlaybackSettings()
                    PlaybackAction.Close -> stopAndBack()
                    else -> viewModel.handlePlaybackAction(action)
                }
            }
        }
    val onCycleResizeModeCallback =
        remember(viewModel, platformCapabilities.desktopResizeModes) {
            { viewModel.cycleResizeMode(twoState = platformCapabilities.desktopResizeModes) }
        }
    val onPlayerSourceBoundsChangedCallback = remember { { bounds: Rect? -> playerSourceBounds = bounds } }
    val onPictureInPictureModeChangedCallback =
        remember(viewModel) {
            { inPictureInPicture: Boolean ->
                isInPictureInPicture = inPictureInPicture
                viewModel.setPictureInPictureMode(inPictureInPicture)
            }
        }

    val selectedTarget by viewModel.selectedPlaybackTarget.collectAsStateWithLifecycle()
    val playerClockEnabled by viewModel.playerClockEnabled.collectAsStateWithLifecycle()
    val selectedOfflineDownloadId = selectedTarget.offlineDownloadId
    val playerError = state as? PlayerUiState.Error
    val onKidsPlay =
        remember(viewModel) {
            {
                if (viewModel.playbackState.value.status == PlaybackStatus.Completed) {
                    viewModel.replayCurrentPlayback()
                    Unit
                } else {
                    viewModel.play()
                }
            }
        }
    // Blocking errors and mandatory recovery need the watch page's actions.
    val kidsRequiresWatchPage = playerError != null || softwareRecovery != null
    LaunchedEffect(kidsRequiresWatchPage) {
        if (kidsRequiresWatchPage) {
            kidsMinimized = false
            kidsFullscreen = false
        }
    }
    val onKidsMinimize =
        remember(viewModel) {
            {
                if (viewModel.state.value !is PlayerUiState.Error && viewModel.softwarePlaybackRecovery.value == null) {
                    val picker = (viewModel.state.value as? PlayerUiState.Content)?.pickerVisible
                    if (picker != null && picker != PlayerPicker.None) viewModel.hidePicker()
                    kidsFullscreen = false
                    kidsMinimized = true
                }
            }
        }
    LaunchedEffect(viewModel, kidsLaunchRequest?.requestId) {
        val request = kidsLaunchRequest ?: return@LaunchedEffect
        if (request.requestId == firstKidsLaunchRequestId) return@LaunchedEffect
        kidsMinimized = false
        kidsFullscreen = false
        // Cancelling this effect cancels a waiting admission and leaves current playback intact.
        if (viewModel.acceptKidsLaunch(request.itemId, request.options)) {
            kidsWatchViewModel?.acceptExternalLaunch(request.itemId, request.options.offlineDownloadId)
        }
    }
    LaunchedEffect(
        kidsWatchViewModel,
        selectedTarget,
        playerError,
    ) {
        if (
            playerError?.error == PlaybackError.OfflineArtifactUnavailable &&
            selectedOfflineDownloadId != null &&
            viewModel.state.value == playerError &&
            viewModel.selectedPlaybackTarget.value == selectedTarget
        ) {
            kidsWatchViewModel?.removeOfflineTarget(selectedOfflineDownloadId)
        }
    }

    if (kidsWatchViewModel != null && kidsState != null) {
        val kidsCapabilities =
            platformCapabilities.copy(
                playerFullscreenControl = true,
            )
        CompositionLocalProvider(
            LocalPlatformCapabilities provides kidsCapabilities,
            LocalFullscreenToggle provides
                FullscreenToggleState(
                    isFullscreen = kidsFullscreen,
                    toggleFullscreen = { kidsFullscreen = !kidsFullscreen },
                    exitFullscreen = { kidsFullscreen = false },
                ),
        ) {
            KidsWatchContent(
                state = presentationState,
                kidsState = kidsState,
                session = session,
                controller = viewModel.currentPlayerController,
                playbackStateFlow = viewModel.playbackState,
                isInPictureInPicture = isInPictureInPicture,
                onBack = onBackAction,
                onPlay = onKidsPlay,
                onPause = viewModel::pause,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSeekTo = viewModel::seekTo,
                onReplay = { viewModel.replayCurrentPlayback() },
                onRetry = viewModel::retry,
                onShowPicker = viewModel::showPicker,
                onHidePicker = viewModel::hidePicker,
                onSelectBackend = viewModel::selectBackend,
                onSelectAudio = viewModel::selectAudio,
                onAdjustAudioTiming = viewModel::adjustAudioTiming,
                onResetAudioTiming = viewModel::resetAudioTiming,
                onSelectSubtitle = viewModel::selectSubtitle,
                onSelectLocalSubtitle = viewModel::selectLocalSubtitle,
                onSelectOfflineSidecar = viewModel::selectOfflineSidecar,
                onAdjustSubtitleTiming = viewModel::adjustSubtitleTiming,
                onResetSubtitleTiming = viewModel::resetSubtitleTiming,
                onSelectQuality = viewModel::selectQuality,
                onSelectQualityPolicy = viewModel::selectQuality,
                onClearQualityOverride = viewModel::clearQualityOverride,
                onSetPlaybackSpeed = viewModel::setPlaybackSpeed,
                onSetSubtitleStyle = viewModel::setSubtitleStyle,
                onSetResizeMode = viewModel::setResizeMode,
                onPlaybackAction = onPlaybackActionCallback,
                onDismissPlaybackGuidance = viewModel::dismissPlaybackGuidance,
                onOpenPlaybackSettings = ::stopAndOpenPlaybackSettings,
                onSelectAsset = { item ->
                    if (
                        viewModel.selectPlaybackAsset(
                            itemId = item.card.id,
                            offlineDownloadId = item.offlineDownloadId,
                            restartFromBeginning = false,
                        )
                    ) {
                        kidsWatchViewModel.selectAccepted(item.card.id)
                    }
                },
                onRetryCatalogue = kidsWatchViewModel::retryCatalogue,
                onRetryOffline = kidsWatchViewModel::retryOfflineQualification,
                onOpenDownloads = ::stopAndOpenDownloads,
                onPictureInPictureModeChanged = onPictureInPictureModeChangedCallback,
                playerSourceBounds = playerSourceBounds,
                onPlayerSourceBoundsChanged = onPlayerSourceBoundsChangedCallback,
                modifier = modifier,
                minimized = kidsMinimized,
                // Only the account-scoped Kids host can keep browsing underneath a minimized player.
                onMinimize = onKidsMinimize.takeIf { kidsLaunchRequest != null && !kidsRequiresWatchPage },
                onRestore = { kidsMinimized = false },
                miniPlayerRailWidth = miniPlayerRailWidth,
                miniPlayerBottomPadding = miniPlayerBottomPadding,
            )
        }
    } else {
        PlayerContent(
            state = presentationState,
            session = session,
            controller = viewModel.currentPlayerController,
            playbackStateFlow = viewModel.playbackState,
            runtimeDiagnosticsFlow = viewModel.runtimeDiagnostics,
            playbackItemId = onPlaybackItemId,
            startWithPlaybackInfoOverlay = viewModel.startWithPlaybackInfoOverlay,
            playerClockEnabled = playerClockEnabled,
            onBack = onBackAction,
            onPlay = viewModel::play,
            onPause = viewModel::pause,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSetVolume = viewModel::setVolume,
            onToggleMute = viewModel::toggleMute,
            onSeekTo = viewModel::seekTo,
            onRetry = viewModel::retry,
            onShowPicker = viewModel::showPicker,
            onHidePicker = viewModel::hidePicker,
            onSelectBackend = viewModel::selectBackend,
            onSelectAudio = viewModel::selectAudio,
            onAdjustAudioTiming = viewModel::adjustAudioTiming,
            onResetAudioTiming = viewModel::resetAudioTiming,
            onSelectSubtitle = viewModel::selectSubtitle,
            onSelectLocalSubtitle = viewModel::selectLocalSubtitle,
            onSelectOfflineSidecar = viewModel::selectOfflineSidecar,
            onAdjustSubtitleTiming = viewModel::adjustSubtitleTiming,
            onResetSubtitleTiming = viewModel::resetSubtitleTiming,
            onSelectQuality = viewModel::selectQuality,
            onSelectQualityPolicy = viewModel::selectQuality,
            onClearQualityOverride = viewModel::clearQualityOverride,
            onDismissPlaybackGuidance = viewModel::dismissPlaybackGuidance,
            onOpenPlaybackSettings = ::stopAndOpenPlaybackSettings,
            onPlaybackAction = onPlaybackActionCallback,
            onSetPlaybackSpeed = viewModel::setPlaybackSpeed,
            onSetSubtitleStyle = viewModel::setSubtitleStyle,
            onSetResizeMode = viewModel::setResizeMode,
            onPlayQueueItem = viewModel::playQueueItem,
            onPrevious = viewModel::playPrevious,
            onShuffleQueue = viewModel::shuffleQueue,
            onSkipCurrentSegment = viewModel::skipCurrentSegment,
            onPlayNext = viewModel::playNext,
            onConfirmStillWatching = viewModel::confirmStillWatching,
            onCycleResizeMode = onCycleResizeModeCallback,
            isInPictureInPicture = isInPictureInPicture,
            playerSourceBounds = playerSourceBounds,
            onPlayerSourceBoundsChanged = onPlayerSourceBoundsChangedCallback,
            onPictureInPictureModeChanged = onPictureInPictureModeChangedCallback,
            modifier = modifier,
        )
    }
    softwareRecovery?.takeUnless { it.switching || isInPictureInPicture }?.let { prompt ->
        SoftwarePlaybackRecoveryDialog(
            prompt = prompt,
            onSwitch = { viewModel.switchSoftwarePlaybackRecovery(prompt.token) },
            onContinue = { viewModel.continueSoftwarePlaybackRecovery(prompt.token) },
            onStop = {
                if (viewModel.softwarePlaybackRecovery.value?.token == prompt.token) stopAndBack()
            },
        )
    }
}
