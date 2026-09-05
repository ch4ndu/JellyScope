// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import kotlinx.coroutines.flow.collect
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PlayerScreen(
    session: Session,
    itemId: String,
    startPositionTicks: Long,
    mediaSourceId: String?,
    initialAudioStreamIndex: Int? = null,
    initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
    queue: List<String> = emptyList(),
    offlineDownloadId: DownloadId? = null,
    onBack: () -> Unit,
    onOpenPlaybackSettings: () -> Unit = {},
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
    var isInPictureInPicture by remember { mutableStateOf(false) }
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

    PlayerContent(
        state = state,
        session = session,
        controller = viewModel.currentPlayerController,
        playbackStateFlow = viewModel.playbackState,
        runtimeDiagnosticsFlow = viewModel.runtimeDiagnostics,
        playbackItemId = onPlaybackItemId,
        startWithPlaybackInfoOverlay = viewModel.startWithPlaybackInfoOverlay,
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
