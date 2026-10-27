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
        viewModel.accept(PlayerIntent.Stop)
        currentOnBack()
    }

    fun stopAndOpenPlaybackSettings() {
        if (backDispatched) {
            return
        }
        backDispatched = true
        viewModel.accept(PlayerIntent.Stop)
        currentOnOpenPlaybackSettings()
    }

    fun stopAndOpenDownloads() {
        if (backDispatched) {
            return
        }
        backDispatched = true
        viewModel.accept(PlayerIntent.Stop)
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
                    else -> viewModel.accept(PlayerIntent.HandlePlaybackAction(action))
                }
            }
        }
    val onCycleResizeModeCallback =
        remember(viewModel, platformCapabilities.desktopResizeModes) {
            { viewModel.accept(PlayerIntent.CycleResizeMode(twoState = platformCapabilities.desktopResizeModes)) }
        }
    val onPlayerSourceBoundsChangedCallback = remember { { bounds: Rect? -> playerSourceBounds = bounds } }
    val onPictureInPictureModeChangedCallback =
        remember(viewModel) {
            { inPictureInPicture: Boolean ->
                isInPictureInPicture = inPictureInPicture
                viewModel.accept(PlayerIntent.PictureInPictureChanged(inPictureInPicture))
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
                    viewModel.accept(PlayerIntent.ReplayCurrent)
                    Unit
                } else {
                    viewModel.accept(PlayerIntent.Play)
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
                    if (picker != null && picker != PlayerPicker.None) viewModel.accept(PlayerIntent.HidePicker)
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
                onPause = { viewModel.accept(PlayerIntent.Pause) },
                onTogglePlayPause = { viewModel.accept(PlayerIntent.TogglePlayPause) },
                onSeekTo = { value -> viewModel.accept(PlayerIntent.SeekTo(value)) },
                onReplay = { viewModel.accept(PlayerIntent.ReplayCurrent) },
                onRetry = { viewModel.accept(PlayerIntent.Retry) },
                onShowPicker = { value -> viewModel.accept(PlayerIntent.ShowPicker(value)) },
                onHidePicker = { viewModel.accept(PlayerIntent.HidePicker) },
                onSelectBackend = { value -> viewModel.accept(PlayerIntent.SelectBackend(value)) },
                onSelectAudio = { value -> viewModel.accept(PlayerIntent.SelectAudio(value)) },
                onAdjustAudioTiming = { value -> viewModel.accept(PlayerIntent.AdjustAudioTiming(value)) },
                onResetAudioTiming = { viewModel.accept(PlayerIntent.ResetAudioTiming) },
                onSelectSubtitle = { value -> viewModel.accept(PlayerIntent.SelectSubtitle(value)) },
                onSelectLocalSubtitle = { value -> viewModel.accept(PlayerIntent.SelectLocalSubtitle(value)) },
                onSelectOfflineSidecar = { viewModel.accept(PlayerIntent.SelectOfflineSidecar) },
                onAdjustSubtitleTiming = { value -> viewModel.accept(PlayerIntent.AdjustSubtitleTiming(value)) },
                onResetSubtitleTiming = { viewModel.accept(PlayerIntent.ResetSubtitleTiming) },
                onSelectQuality = { value -> viewModel.accept(PlayerIntent.SelectQualityBitrate(value)) },
                onSelectQualityPolicy = { value -> viewModel.accept(PlayerIntent.SelectQuality(value)) },
                onClearQualityOverride = { viewModel.accept(PlayerIntent.ClearQualityOverride) },
                onSetPlaybackSpeed = { value -> viewModel.accept(PlayerIntent.SetPlaybackSpeed(value)) },
                onSetSubtitleStyle = { value -> viewModel.accept(PlayerIntent.SetSubtitleStyle(value)) },
                onSetResizeMode = { value -> viewModel.accept(PlayerIntent.SetResizeMode(value)) },
                onPlaybackAction = onPlaybackActionCallback,
                onDismissPlaybackGuidance = { viewModel.accept(PlayerIntent.DismissPlaybackGuidance) },
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
            onPlay = { viewModel.accept(PlayerIntent.Play) },
            onPause = { viewModel.accept(PlayerIntent.Pause) },
            onTogglePlayPause = { viewModel.accept(PlayerIntent.TogglePlayPause) },
            onSetVolume = { value -> viewModel.accept(PlayerIntent.SetVolume(value)) },
            onToggleMute = { viewModel.accept(PlayerIntent.ToggleMute) },
            onSeekTo = { value -> viewModel.accept(PlayerIntent.SeekTo(value)) },
            onRetry = { viewModel.accept(PlayerIntent.Retry) },
            onShowPicker = { value -> viewModel.accept(PlayerIntent.ShowPicker(value)) },
            onHidePicker = { viewModel.accept(PlayerIntent.HidePicker) },
            onSelectBackend = { value -> viewModel.accept(PlayerIntent.SelectBackend(value)) },
            onSelectAudio = { value -> viewModel.accept(PlayerIntent.SelectAudio(value)) },
            onAdjustAudioTiming = { value -> viewModel.accept(PlayerIntent.AdjustAudioTiming(value)) },
            onResetAudioTiming = { viewModel.accept(PlayerIntent.ResetAudioTiming) },
            onSelectSubtitle = { value -> viewModel.accept(PlayerIntent.SelectSubtitle(value)) },
            onSelectLocalSubtitle = { value -> viewModel.accept(PlayerIntent.SelectLocalSubtitle(value)) },
            onSelectOfflineSidecar = { viewModel.accept(PlayerIntent.SelectOfflineSidecar) },
            onAdjustSubtitleTiming = { value -> viewModel.accept(PlayerIntent.AdjustSubtitleTiming(value)) },
            onResetSubtitleTiming = { viewModel.accept(PlayerIntent.ResetSubtitleTiming) },
            onSelectQuality = { value -> viewModel.accept(PlayerIntent.SelectQualityBitrate(value)) },
            onSelectQualityPolicy = { value -> viewModel.accept(PlayerIntent.SelectQuality(value)) },
            onClearQualityOverride = { viewModel.accept(PlayerIntent.ClearQualityOverride) },
            onDismissPlaybackGuidance = { viewModel.accept(PlayerIntent.DismissPlaybackGuidance) },
            onOpenPlaybackSettings = ::stopAndOpenPlaybackSettings,
            onPlaybackAction = onPlaybackActionCallback,
            onSetPlaybackSpeed = { value -> viewModel.accept(PlayerIntent.SetPlaybackSpeed(value)) },
            onSetSubtitleStyle = { value -> viewModel.accept(PlayerIntent.SetSubtitleStyle(value)) },
            onSetResizeMode = { value -> viewModel.accept(PlayerIntent.SetResizeMode(value)) },
            onPlayQueueItem = { value -> viewModel.accept(PlayerIntent.PlayQueueItem(value)) },
            onPrevious = { viewModel.accept(PlayerIntent.PlayPrevious) },
            onShuffleQueue = { viewModel.accept(PlayerIntent.ShuffleQueue) },
            onSkipCurrentSegment = { viewModel.accept(PlayerIntent.SkipCurrentSegment) },
            onPlayNext = { auto, generation -> viewModel.accept(PlayerIntent.PlayNext(auto, generation)) },
            onConfirmStillWatching = { viewModel.accept(PlayerIntent.ConfirmStillWatching) },
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
            onSwitch = { viewModel.accept(PlayerIntent.SwitchSoftwareRecovery(prompt.token)) },
            onContinue = { viewModel.accept(PlayerIntent.ContinueSoftwareRecovery(prompt.token)) },
            onStop = {
                if (viewModel.softwarePlaybackRecovery.value?.token == prompt.token) stopAndBack()
            },
        )
    }
}
