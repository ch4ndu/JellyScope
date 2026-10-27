// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.screen.player.PlayerIntent
import com.jellyscope.ui.screen.player.PlayerLaunchOptions
import com.jellyscope.ui.screen.player.PlayerUiState
import com.jellyscope.ui.screen.player.PlayerViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TvPlayerScreen(
    session: Session,
    itemId: String,
    startPositionTicks: Long,
    mediaSourceId: String?,
    initialAudioStreamIndex: Int?,
    initialSubtitleSelection: SubtitleSelectionIntent,
    queue: List<String>,
    offlineDownloadId: DownloadId? = null,
    offlineRestartFromBeginning: Boolean = false,
    onBack: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    modifier: Modifier = Modifier,
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
                    ),
                )
            },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val softwareRecovery by viewModel.softwarePlaybackRecovery.collectAsStateWithLifecycle()
    val presentationState = if (softwareRecovery?.switching == true) PlayerUiState.Loading else state
    val matchDisplayRefreshRate by viewModel.matchDisplayRefreshRate.collectAsStateWithLifecycle()
    val playerClockEnabled by viewModel.playerClockEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val controllerScope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    val displayModeController =
        remember(activity, viewModel.currentPlayerController, controllerScope) {
            tvDisplayModeController(
                activity = activity,
                playerController = viewModel.currentPlayerController,
                scope = controllerScope,
            )
        }
    val presentationFrameRate = (state as? PlayerUiState.Content)?.videoPresentation?.frameRate
    val playbackFailed = state is PlayerUiState.Error
    var backDispatched by remember(viewModel) { mutableStateOf(false) }

    fun stopAndBack() {
        if (backDispatched) {
            return
        }
        backDispatched = true
        displayModeController.reset()
        viewModel.accept(PlayerIntent.Stop)
        onBack()
    }

    fun stopAndOpenPlaybackSettings() {
        if (backDispatched) {
            return
        }
        backDispatched = true
        displayModeController.reset()
        viewModel.accept(PlayerIntent.Stop)
        onOpenPlaybackSettings()
    }

    // Backend resolution can replace the controller while this destination
    // remains mounted. Dispose the display-mode owner with that controller,
    // but keep the ViewModel alive for the replacement playback session.
    DisposableEffect(displayModeController) {
        onDispose { displayModeController.dispose() }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    // HOME backgrounds the Activity without changing the route, so the
    // composition-scoped disposal above never fires and the player kept
    // playing behind the launcher. Backgrounding DISMISSES the player:
    // stop playback (reporting the resume position) and leave the route,
    // so reopening the app lands on the screen the player came from.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, displayModeController) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    stopAndBack()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.playbackEnded.collect {
            stopAndBack()
        }
    }

    LaunchedEffect(matchDisplayRefreshRate, presentationFrameRate, playbackFailed) {
        if (matchDisplayRefreshRate && !playbackFailed) {
            displayModeController.apply(presentationFrameRate)
        } else {
            displayModeController.reset()
        }
    }

    val onPlaybackItemId = remember(viewModel) { { (viewModel.state.value as? PlayerUiState.Content)?.playbackItemId } }
    val onTogglePlayPause = remember(viewModel) { { viewModel.accept(PlayerIntent.TogglePlayPause) } }
    val onNextAction =
        remember(viewModel) {
            {
                viewModel.accept(PlayerIntent.PlayNext(auto = false))
                Unit
            }
        }
    val onStopAction = remember(viewModel) { { stopAndBack() } }
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
    val onOpenSettingsAction = remember(viewModel) { { stopAndOpenPlaybackSettings() } }
    val onBackAction = remember(viewModel) { { stopAndBack() } }

    TvPlaybackWakefulnessEffect(activity)
    TvPlayerContent(
        session = session,
        initialItemId = itemId,
        state = presentationState,
        playbackStateFlow = viewModel.playbackState,
        controller = viewModel.currentPlayerController,
        runtimeDiagnosticsFlow = viewModel.runtimeDiagnostics,
        playbackItemId = onPlaybackItemId,
        startWithPlaybackInfoOverlay = viewModel.startWithPlaybackInfoOverlay,
        playerClockEnabled = playerClockEnabled,
        onTogglePlayPause = onTogglePlayPause,
        onPlay = { viewModel.accept(PlayerIntent.Play) },
        onPause = { viewModel.accept(PlayerIntent.Pause) },
        onNext = onNextAction,
        onPrevious = { viewModel.accept(PlayerIntent.PlayPrevious) },
        onStop = onStopAction,
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
        onPlaybackAction = onPlaybackActionCallback,
        onOpenPlaybackSettings = onOpenSettingsAction,
        onPlayQueueItem = { value -> viewModel.accept(PlayerIntent.PlayQueueItem(value)) },
        onSeekTo = { value -> viewModel.accept(PlayerIntent.SeekTo(value)) },
        onSkipCurrentSegment = { viewModel.accept(PlayerIntent.SkipCurrentSegment) },
        onSetPlaybackSpeed = { value -> viewModel.accept(PlayerIntent.SetPlaybackSpeed(value)) },
        onSetSubtitleStyle = { value -> viewModel.accept(PlayerIntent.SetSubtitleStyle(value)) },
        onSelectResizeMode = { value -> viewModel.accept(PlayerIntent.SetResizeMode(value)) },
        onPlayNext = { auto, generation -> viewModel.accept(PlayerIntent.PlayNext(auto, generation)) },
        onConfirmStillWatching = { viewModel.accept(PlayerIntent.ConfirmStillWatching) },
        onDismissPlaybackGuidance = { viewModel.accept(PlayerIntent.DismissPlaybackGuidance) },
        onRetry = { viewModel.accept(PlayerIntent.Retry) },
        onBack = onBackAction,
        modifier = modifier,
    )
    softwareRecovery?.takeUnless { it.switching }?.let { prompt ->
        TvSoftwarePlaybackRecoveryDialog(
            prompt = prompt,
            onSwitch = { viewModel.accept(PlayerIntent.SwitchSoftwareRecovery(prompt.token)) },
            onContinue = { viewModel.accept(PlayerIntent.ContinueSoftwareRecovery(prompt.token)) },
            onStop = {
                if (viewModel.softwarePlaybackRecovery.value?.token == prompt.token) stopAndBack()
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
