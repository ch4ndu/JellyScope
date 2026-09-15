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
                    ),
                )
            },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val softwareRecovery by viewModel.softwarePlaybackRecovery.collectAsStateWithLifecycle()
    val presentationState = if (softwareRecovery?.switching == true) PlayerUiState.Loading else state
    val matchDisplayRefreshRate by viewModel.matchDisplayRefreshRate.collectAsStateWithLifecycle()
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
        viewModel.stop()
        onBack()
    }

    fun stopAndOpenPlaybackSettings() {
        if (backDispatched) {
            return
        }
        backDispatched = true
        displayModeController.reset()
        viewModel.stop()
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
    val onTogglePlayPause = remember(viewModel) { viewModel::togglePlayPause }
    val onNextAction =
        remember(viewModel) {
            {
                viewModel.playNext(auto = false)
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
                    else -> viewModel.handlePlaybackAction(action)
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
        onTogglePlayPause = onTogglePlayPause,
        onPlay = viewModel::play,
        onPause = viewModel::pause,
        onNext = onNextAction,
        onPrevious = viewModel::playPrevious,
        onStop = onStopAction,
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
        onPlaybackAction = onPlaybackActionCallback,
        onOpenPlaybackSettings = onOpenSettingsAction,
        onPlayQueueItem = viewModel::playQueueItem,
        onSeekTo = viewModel::seekTo,
        onSkipCurrentSegment = viewModel::skipCurrentSegment,
        onSetPlaybackSpeed = viewModel::setPlaybackSpeed,
        onSetSubtitleStyle = viewModel::setSubtitleStyle,
        onSelectResizeMode = viewModel::setResizeMode,
        onPlayNext = viewModel::playNext,
        onConfirmStillWatching = viewModel::confirmStillWatching,
        onDismissPlaybackGuidance = viewModel::dismissPlaybackGuidance,
        onRetry = viewModel::retry,
        onBack = onBackAction,
        modifier = modifier,
    )
    softwareRecovery?.takeUnless { it.switching }?.let { prompt ->
        TvSoftwarePlaybackRecoveryDialog(
            prompt = prompt,
            onSwitch = { viewModel.switchSoftwarePlaybackRecovery(prompt.token) },
            onContinue = { viewModel.continueSoftwarePlaybackRecovery(prompt.token) },
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
