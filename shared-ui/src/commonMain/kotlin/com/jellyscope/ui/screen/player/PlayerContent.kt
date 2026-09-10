// SPDX-License-Identifier: MPL-2.0

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.jellyscope.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.platform.LocalFullscreenToggle
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.platform.LocalPlayerCursorState
import com.jellyscope.ui.platform.LocalPlayerKeyCommandBridge
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
fun PlayerContent(
    state: PlayerUiState,
    session: Session,
    controller: com.jellyscope.core.domain.playback.PlayerController,
    playbackStateFlow: StateFlow<PlaybackState> = controller.playbackState,
    runtimeDiagnosticsFlow: StateFlow<PlaybackRuntimeDiagnostics> = controller.runtimeDiagnostics,
    // Synchronous identity prevents a seek commit from crossing a queue switch.
    playbackItemId: () -> String?,
    startWithPlaybackInfoOverlay: Boolean = false,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onTogglePlayPause: () -> Unit = {
        if (playbackStateFlow.value.status == PlaybackStatus.Playing) onPause() else onPlay()
    },
    onSetVolume: (Int) -> Unit = {},
    onToggleMute: () -> Unit = {},
    onSeekTo: (Long) -> Unit,
    onRetry: () -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    onHidePicker: () -> Unit,
    onSelectBackend: (PlayerBackend) -> Unit = {},
    onSelectAudio: (Int) -> Unit,
    onAdjustAudioTiming: (Long) -> Unit = {},
    onResetAudioTiming: () -> Unit = {},
    onSelectSubtitle: (Int?) -> Unit,
    onSelectLocalSubtitle: (String) -> Unit = {},
    onSelectOfflineSidecar: () -> Unit = {},
    onAdjustSubtitleTiming: (Long) -> Unit = {},
    onResetSubtitleTiming: () -> Unit = {},
    onSelectQuality: (Long?) -> Unit,
    onSelectQualityPolicy: (PlaybackQualityPolicy) -> Unit = { policy -> onSelectQuality(policy.maxBitrateBps) },
    onClearQualityOverride: () -> Unit = {},
    onDismissPlaybackGuidance: () -> Unit = {},
    onOpenPlaybackSettings: () -> Unit = {},
    onPlaybackAction: (PlaybackAction) -> Unit = {},
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetSubtitleStyle: (SubtitleStyle) -> Unit,
    onSetResizeMode: (PlayerResizeMode) -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    onPrevious: () -> Unit = {},
    onShuffleQueue: () -> Unit,
    onSkipCurrentSegment: () -> Unit = {},
    onPlayNext: (Boolean, Long?) -> Boolean = { _, _ -> false },
    onConfirmStillWatching: () -> Unit = {},
    onCycleResizeMode: () -> Unit = {},
    isInPictureInPicture: Boolean = false,
    playerSourceBounds: Rect? = null,
    onPlayerSourceBoundsChanged: (Rect?) -> Unit = {},
    onPictureInPictureModeChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val stateContent = state as? PlayerUiState.Content
    val playbackState =
        stateContent?.playbackState
            ?: PlaybackState(
                status = PlaybackStatus.Loading,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
            )
    // Visible leaves collect live playback; this snapshot changes only at UI edges.
    val content = stateContent
    var controlsVisible by remember { mutableStateOf(true) }
    var playNextRequested by remember { mutableStateOf(false) }
    var dismissedUpNextIdentity by remember { mutableStateOf<UpNextDismissalIdentity?>(null) }
    var debugOverlayVisible by rememberSaveable { mutableStateOf(startWithPlaybackInfoOverlay) }
    var gestureHud by remember { mutableStateOf<PlayerGestureHud?>(null) }
    var audioNoticeVisible by rememberSaveable { mutableStateOf(false) }
    var subtitleNoticeVisible by rememberSaveable { mutableStateOf(false) }
    var backendNoticeVisible by rememberSaveable { mutableStateOf(false) }
    val platformCapabilities = LocalPlatformCapabilities.current
    val fullscreenToggle = LocalFullscreenToggle.current
    val playerCursorState = LocalPlayerCursorState.current
    val playerKeyCommandBridge = LocalPlayerKeyCommandBridge.current
    var cursorVisible by remember { mutableStateOf(true) }
    var pointerScrubActive by remember { mutableStateOf(false) }
    var controlsScrollActive by remember { mutableStateOf(false) }
    // Plain state avoids a layout-to-snapshot feedback loop.
    val lastPublishedSourceBounds = remember { PlayerSourceBoundsHolder() }
    val holdSeekScope = rememberCoroutineScope()
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentPlaybackItemId by rememberUpdatedState(playbackItemId)
    val holdSeek =
        remember {
            HoldSeekController(
                scope = holdSeekScope,
                itemId = { currentPlaybackItemId() },
                positionMs = { playbackStateFlow.value.positionMs },
                durationMs = { playbackStateFlow.value.durationMs },
                commit = { target -> currentOnSeekTo(target) },
            )
        }
    // Item changes cancel any pending keyboard seek.
    LaunchedEffect(content?.playbackItemId) {
        holdSeek.cancel()
    }
    DisposableEffect(Unit) {
        onDispose { holdSeek.cancel() }
    }
    val controlsInactivity = remember { InactivitySignal() }
    val cursorInactivity = remember { InactivitySignal() }
    val audioNoticeInactivity = remember { InactivitySignal() }
    val keepControlsAlive = {
        controlsVisible = true
        controlsInactivity.signal()
        cursorVisible = true
        cursorInactivity.signal()
        if (content?.audioUnavailable == true) {
            audioNoticeVisible = true
            audioNoticeInactivity.signal()
        }
    }
    val currentKeepControlsAlive by rememberUpdatedState(keepControlsAlive)

    // Touch movement must not reveal controls before Android claims a back gesture.
    val keepControlsAliveOnly = {
        if (controlsVisible) {
            controlsInactivity.signal()
            if (content?.audioUnavailable == true) {
                audioNoticeVisible = true
                audioNoticeInactivity.signal()
            }
        }
    }
    val currentKeepControlsAliveOnly by rememberUpdatedState(keepControlsAliveOnly)
    val revealCursor = {
        cursorVisible = true
        cursorInactivity.signal()
    }
    PlayerPointerActivityRegistration(
        enabled = platformCapabilities.desktopPlayerControls,
        onActivity = keepControlsAlive,
    )
    val autoHideCursor =
        shouldAutoHidePlayerCursor(
            desktopPlayerControls = platformCapabilities.desktopPlayerControls,
            fullscreen = fullscreenToggle.isFullscreen,
            picker = content?.pickerVisible ?: PlayerPicker.None,
            pictureInPicture = isInPictureInPicture,
        )
    val subtitleClearanceActive =
        content != null &&
            isSubtitleClearanceActive(
                controlsVisible = controlsVisible,
                picker = content.pickerVisible,
                pictureInPicture = isInPictureInPicture,
            )

    fun handleBack() {
        val picker = content?.pickerVisible ?: PlayerPicker.None
        when (playerBackAction(picker, controlsVisible)) {
            PlayerBackAction.ReturnToParentPicker -> onShowPicker(picker.parentPicker())
            PlayerBackAction.ClosePicker -> {
                onHidePicker()
                controlsVisible = true
            }
            PlayerBackAction.HideControls -> controlsVisible = false
            PlayerBackAction.ExitPlayer -> onBack()
        }
    }

    BackHandler(enabled = !isInPictureInPicture, onBack = ::handleBack)

    // Stable identity prevents MediaSession/PiP effects from restarting on recomposition.
    val commandCallbacks =
        remember(onPlay, onPause, onTogglePlayPause, onSeekTo, onPlayNext, onPrevious, onBack, onSetPlaybackSpeed) {
            PlayerPlatformCommandCallbacks(
                play = onPlay,
                pause = onPause,
                toggle = onTogglePlayPause,
                seekTo = onSeekTo,
                next = { onPlayNext(false, null) },
                previous = onPrevious,
                stop = onBack,
                setPlaybackSpeed = onSetPlaybackSpeed,
            )
        }

    PlayerPlatformEffects(
        controller = controller,
        content = content,
        sourceBounds = playerSourceBounds,
        commandCallbacks = commandCallbacks,
        onPictureInPictureModeChanged = onPictureInPictureModeChanged,
        onCloseFromPictureInPicture = onBack,
        onBackgrounded = onBack,
    )

    PlayerNowPlayingEffects(
        controller = controller,
        content = content,
        onPlay = onPlay,
        onPause = onPause,
        onToggle = commandCallbacks.toggle,
        onSeekTo = onSeekTo,
    )

    // Keep controls visible throughout a strip scroll.
    LaunchedEffect(controlsVisible, playbackState.status, content?.pickerVisible, controlsScrollActive) {
        if (controlsVisible && playbackState.status == PlaybackStatus.Playing && !controlsScrollActive) {
            controlsInactivity.signal()
            controlsInactivity.awaitInactivity(PLAYER_CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(playbackState.status) {
        if (playbackState.status == PlaybackStatus.Playing) {
            playNextRequested = false
        } else if (!playNextRequested) {
            controlsVisible = true
        }
    }
    LaunchedEffect(content?.pickerVisible) {
        if (content?.pickerVisible == PlayerPicker.None) {
            controlsVisible = true
        }
    }
    LaunchedEffect(
        content?.pickerVisible,
        content?.subtitleOptions?.size,
        content?.localSubtitleOptions?.size,
        content?.offlineSidecarOption,
        content?.subtitleStyleable,
    ) {
        val picker = content?.pickerVisible
        val hasSubtitleChoice = content?.hasSubtitlePickerChoice() == true
        if (
            picker == PlayerPicker.Subtitles &&
            !hasSubtitleChoice
        ) {
            onHidePicker()
            controlsVisible = true
        }
        if (picker == PlayerPicker.SubtitleStyle && !content.subtitleStyleable) {
            onHidePicker()
            controlsVisible = true
        }
    }
    LaunchedEffect(autoHideCursor) {
        if (autoHideCursor) {
            cursorVisible = true
            cursorInactivity.signal()
            while (true) {
                cursorInactivity.awaitInactivity(PLAYER_CURSOR_AUTO_HIDE_MS)
                cursorVisible = false
            }
        } else {
            cursorVisible = true
        }
    }
    LaunchedEffect(playerCursorState, autoHideCursor, cursorVisible) {
        playerCursorState.setVisible(!autoHideCursor || cursorVisible)
    }
    DisposableEffect(playerCursorState) {
        onDispose {
            playerCursorState.setVisible(true)
        }
    }
    LaunchedEffect(gestureHud) {
        if (gestureHud != null) {
            delay(GESTURE_HUD_AUTO_HIDE_MS)
            gestureHud = null
        }
    }
    LaunchedEffect(content?.audioUnavailable) {
        if (content?.audioUnavailable == true) {
            audioNoticeVisible = true
            audioNoticeInactivity.signal()
            while (true) {
                audioNoticeInactivity.awaitInactivity(AUDIO_UNAVAILABLE_NOTICE_MS)
                audioNoticeVisible = false
            }
        } else {
            audioNoticeVisible = false
        }
    }
    LaunchedEffect(content?.subtitleNotice?.token) {
        if (content?.subtitleNotice != null) {
            subtitleNoticeVisible = true
            delay(SUBTITLE_UNAVAILABLE_NOTICE_MS)
            subtitleNoticeVisible = false
        } else {
            subtitleNoticeVisible = false
        }
    }
    LaunchedEffect(content?.backendNotice?.token) {
        if (content?.backendNotice != null) {
            backendNoticeVisible = true
            delay(BACKEND_FALLBACK_NOTICE_MS)
            backendNoticeVisible = false
        } else {
            backendNoticeVisible = false
        }
    }
    PlayerKeyCommandRegistration(
        bridge = playerKeyCommandBridge,
        enabled = content != null,
        content = content,
        playbackStateFlow = playbackStateFlow,
        fullscreenToggle = fullscreenToggle,
        holdSeek = holdSeek,
        pointerScrubActive = pointerScrubActive,
        onBack = ::handleBack,
        onPlay = onPlay,
        onPause = onPause,
        onSetVolume = onSetVolume,
        onToggleMute = onToggleMute,
        onShowControls = keepControlsAlive,
    )

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                // Pointer moves bubble here from all overlay leaves.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.type == PointerEventType.Move) {
                                // Hover reveals; touch movement only keeps visible controls alive.
                                if (event.changes.firstOrNull()?.type == PointerType.Mouse) {
                                    currentKeepControlsAlive()
                                } else {
                                    currentKeepControlsAliveOnly()
                                }
                            }
                        }
                    }
                }.playerKeyboardShortcuts(
                    enabled = platformCapabilities.playerKeyboardShortcuts && playerKeyCommandBridge == null,
                    content = content,
                    playbackStateFlow = playbackStateFlow,
                    fullscreenToggle = fullscreenToggle,
                    holdSeek = holdSeek,
                    pointerScrubActive = pointerScrubActive,
                    onBack = ::handleBack,
                    onPlay = onPlay,
                    onPause = onPause,
                    onSetVolume = onSetVolume,
                    onToggleMute = onToggleMute,
                ),
    ) {
        val compactPlayerLayout = WindowWidthTier.fromAvailableWidth(maxWidth) == WindowWidthTier.Compact
        PlayerSurface(
            controller = controller,
            modifier =
                Modifier
                    .fillMaxSize()
                    .zIndex(PlayerOverlayLayer.VIDEO)
                    .onGloballyPositioned { coordinates ->
                        // Publish only changed bounds to avoid a layout feedback loop.
                        val bounds = coordinates.boundsInWindow()
                        if (bounds != lastPublishedSourceBounds.value) {
                            lastPublishedSourceBounds.value = bounds
                            onPlayerSourceBoundsChanged(bounds)
                        }
                    },
            resizeMode = content?.resizeMode ?: PlayerResizeMode.Fit,
            subtitleStyle = content?.subtitleStyle ?: SubtitleStyle(),
            subtitleClearanceActive = subtitleClearanceActive,
            pictureInPictureRequiresLinearPlayback =
                content?.pictureInPictureRequiresLinearPlayback == true,
        )
        PlayerControlsOverlay(
            modifier = Modifier.size(maxWidth, maxHeight),
        ) {
            if (content != null && !isInPictureInPicture) {
                PlayerGestureLayer(
                    content = content,
                    playbackState = { playbackStateFlow.value },
                    onToggleControls = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) {
                            controlsInactivity.signal()
                        }
                        revealCursor()
                    },
                    onToggleFullscreen = {
                        if (platformCapabilities.playerFullscreenControl) {
                            fullscreenToggle.toggleFullscreen()
                        } else {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) {
                                controlsInactivity.signal()
                            }
                        }
                        revealCursor()
                    },
                    onSeekTo = onSeekTo,
                    onSetPlaybackSpeed = onSetPlaybackSpeed,
                    onHud = { hud -> gestureHud = hud },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            when (state) {
                PlayerUiState.Loading ->
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .size(Dimensions.playerProgressIndicatorSize),
                        strokeWidth = Dimensions.progressIndicatorStroke,
                    )
                is PlayerUiState.Error ->
                    PlayerError(
                        retryable = state.retryable,
                        error = state.error,
                        onBack = onBack,
                        onRetry = onRetry,
                        modifier =
                            Modifier
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(Dimensions.screenPadding),
                    )
                is PlayerUiState.Content -> {
                    val playerContent = content ?: state
                    val showPlayerControls =
                        !isInPictureInPicture &&
                            playerContent.pickerVisible == PlayerPicker.None &&
                            controlsVisible
                    if (playbackState.status == PlaybackStatus.Buffering ||
                        playbackState.status == PlaybackStatus.Loading
                    ) {
                        // Loading includes native re-prepare after seek or quality changes.
                        BufferingIndicator(
                            compact = compactPlayerLayout,
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .zIndex(PlayerOverlayLayer.POPUP),
                        )
                    }
                    if (!isInPictureInPicture && debugOverlayVisible) {
                        val expandedDebugOverlay = platformCapabilities.desktopPlayerControls
                        PlayerDebugOverlay(
                            debugInfo = playerContent.debugInfo,
                            playbackStateFlow = playbackStateFlow,
                            runtimeDiagnosticsFlow = runtimeDiagnosticsFlow,
                            expanded = expandedDebugOverlay,
                            compact = compactPlayerLayout,
                            modifier =
                                Modifier
                                    .align(Alignment.TopStart)
                                    // Measure fractions against the full player surface.
                                    .then(
                                        if (expandedDebugOverlay) {
                                            Modifier
                                                .fillMaxWidth(PLAYER_DEBUG_OVERLAY_WIDTH_FRACTION)
                                                .fillMaxHeight(PLAYER_DEBUG_OVERLAY_HEIGHT_FRACTION)
                                        } else {
                                            Modifier
                                        },
                                    ).windowInsetsPadding(WindowInsets.safeDrawing)
                                    .padding(
                                        start = Dimensions.screenPadding,
                                        end = Dimensions.screenPadding,
                                        top = Dimensions.minTouchTarget + Dimensions.formSpacing,
                                    ).zIndex(PlayerOverlayLayer.DEBUG_OVERLAY),
                        )
                    }
                    AnimatedVisibility(
                        visible =
                            !isInPictureInPicture &&
                                playerContent.audioUnavailable &&
                                audioNoticeVisible,
                        enter = slideInVertically { height -> height } + fadeIn(),
                        exit = slideOutVertically { height -> height } + fadeOut(),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(PlayerOverlayLayer.POPUP),
                    ) {
                        AudioUnavailableBanner(
                            onDismiss = { audioNoticeVisible = false },
                            modifier =
                                Modifier
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                        ),
                                    ).padding(
                                        start = Dimensions.screenPadding,
                                        end = Dimensions.screenPadding,
                                        bottom =
                                            if (showPlayerControls) {
                                                Dimensions.playerPrimaryControlSize +
                                                    Dimensions.playerPrimaryControlSize +
                                                    Dimensions.formSpacing +
                                                    Dimensions.formSpacing
                                            } else {
                                                Dimensions.formSpacing
                                            },
                                    ),
                        )
                    }
                    AnimatedVisibility(
                        visible = !isInPictureInPicture && subtitleNoticeVisible,
                        enter = slideInVertically { height -> height } + fadeIn(),
                        exit = slideOutVertically { height -> height } + fadeOut(),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(PlayerOverlayLayer.POPUP),
                    ) {
                        SubtitleUnavailableBanner(
                            message = playerContent.subtitleNotice?.message,
                            modifier =
                                Modifier
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                        ),
                                    ).padding(
                                        start = Dimensions.screenPadding,
                                        end = Dimensions.screenPadding,
                                        bottom =
                                            if (showPlayerControls) {
                                                Dimensions.playerPrimaryControlSize +
                                                    Dimensions.playerPrimaryControlSize +
                                                    Dimensions.formSpacing +
                                                    Dimensions.formSpacing +
                                                    if (playerContent.audioUnavailable && audioNoticeVisible) {
                                                        Dimensions.minTouchTarget + Dimensions.contentSpacing
                                                    } else {
                                                        Dimensions.formSpacing
                                                    }
                                            } else {
                                                Dimensions.formSpacing
                                            },
                                    ),
                        )
                    }
                    AnimatedVisibility(
                        visible = !isInPictureInPicture && backendNoticeVisible,
                        enter = slideInVertically { height -> -height } + fadeIn(),
                        exit = slideOutVertically { height -> -height } + fadeOut(),
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(PlayerOverlayLayer.POPUP),
                    ) {
                        playerContent.backendNotice?.let { notice ->
                            BackendFallbackBanner(
                                notice = notice,
                                modifier =
                                    Modifier
                                        .windowInsetsPadding(WindowInsets.safeDrawing)
                                        .padding(
                                            start = Dimensions.screenPadding,
                                            end = Dimensions.screenPadding,
                                            top = Dimensions.formSpacing,
                                        ),
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = !isInPictureInPicture && playerContent.playbackChangeNotice != null,
                        enter = slideInVertically { height -> -height } + fadeIn(),
                        exit = slideOutVertically { height -> -height } + fadeOut(),
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(PlayerOverlayLayer.POPUP),
                    ) {
                        playerContent.playbackChangeNotice?.let { notice ->
                            PlaybackChangeKeptBanner(
                                notice = notice,
                                onDismiss = { onPlaybackAction(PlaybackAction.Dismiss) },
                                modifier =
                                    Modifier
                                        .windowInsetsPadding(WindowInsets.safeDrawing)
                                        .padding(
                                            start = Dimensions.screenPadding,
                                            end = Dimensions.screenPadding,
                                            top = Dimensions.formSpacing,
                                        ),
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible =
                            !isInPictureInPicture &&
                                playerContent.playbackChangeNotice == null &&
                                (playerContent.playbackActionNotice != null || playerContent.playbackGuidance != null),
                        enter = slideInVertically { height -> height } + fadeIn(),
                        exit = slideOutVertically { height -> height } + fadeOut(),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(PlayerOverlayLayer.POPUP),
                    ) {
                        val noticeModifier =
                            Modifier
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(
                                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                    ),
                                ).padding(
                                    start = Dimensions.screenPadding,
                                    end = Dimensions.screenPadding,
                                    bottom =
                                        if (showPlayerControls) {
                                            Dimensions.playerPrimaryControlSize +
                                                Dimensions.playerPrimaryControlSize +
                                                Dimensions.formSpacing +
                                                Dimensions.formSpacing
                                        } else {
                                            Dimensions.formSpacing
                                        },
                                )
                        playerContent.playbackActionNotice?.let { notice ->
                            PlaybackActionNoticeBanner(
                                notice = notice,
                                onAction = onPlaybackAction,
                                modifier = noticeModifier,
                            )
                        } ?: playerContent.playbackGuidance?.let { guidance ->
                            PlaybackGuidanceBanner(
                                guidance = guidance,
                                onReduce = { guidance.nextLowerQualityRungBps?.let(onSelectQuality) },
                                onOpenPlaybackSettings = onOpenPlaybackSettings,
                                onDismiss = onDismissPlaybackGuidance,
                                modifier = noticeModifier,
                            )
                        }
                    }
                    if (!isInPictureInPicture && playerContent.audioUnavailable && !showPlayerControls) {
                        AudioUnavailableGlyph(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                        ),
                                    ).padding(
                                        start = Dimensions.screenPadding,
                                        bottom = Dimensions.screenPadding,
                                    ).zIndex(PlayerOverlayLayer.POPUP),
                        )
                    }
                    if (!isInPictureInPicture && !controlsVisible) {
                        playerContent.skipPromptSegment?.let { segment ->
                            SkipSegmentButton(
                                segment = segment,
                                onSkipCurrentSegment = onSkipCurrentSegment,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .windowInsetsPadding(WindowInsets.safeDrawing)
                                        .padding(Dimensions.screenPadding)
                                        .zIndex(PlayerOverlayLayer.CHROME),
                            )
                        }
                    }
                    if (!isInPictureInPicture && playerContent.stillWatchingPrompt) {
                        StillWatchingCard(
                            onConfirmStillWatching = onConfirmStillWatching,
                            onDismiss = onBack,
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .windowInsetsPadding(WindowInsets.safeDrawing)
                                    .padding(Dimensions.screenPadding)
                                    .zIndex(PlayerOverlayLayer.MODAL),
                        )
                    }
                    if (!isInPictureInPicture && gestureHud != null) {
                        GestureHud(
                            hud = gestureHud,
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .padding(Dimensions.screenPadding)
                                    .zIndex(PlayerOverlayLayer.POPUP),
                        )
                    }
                    AnimatedVisibility(
                        visible = showPlayerControls,
                        enter =
                            if (platformCapabilities.desktopPlayerControls) {
                                EnterTransition.None
                            } else {
                                slideInVertically { height -> -height } + fadeIn()
                            },
                        exit =
                            if (platformCapabilities.desktopPlayerControls) {
                                ExitTransition.None
                            } else {
                                slideOutVertically { height -> -height } + fadeOut()
                            },
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(PlayerOverlayLayer.CHROME),
                    ) {
                        PlayerTopBar(
                            content = playerContent,
                            onBack = {
                                keepControlsAlive()
                                onBack()
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Black.copy(alpha = PLAYER_TOP_SCRIM_ALPHA),
                                            1f to Color.Transparent,
                                        ),
                                    ).windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                        ),
                                    ).padding(
                                        // Safe drawing already clears system bars.
                                        horizontal = Dimensions.contentSpacing,
                                        vertical = Dimensions.contentSpacing,
                                    ),
                        )
                    }
                    Column(
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .zIndex(PlayerOverlayLayer.CHROME),
                    ) {
                        if (!isInPictureInPicture && !playerContent.stillWatchingPrompt) {
                            playerContent.upNext?.let { upNext ->
                                val dismissalIdentity =
                                    upNext.dismissalIdentity(playerContent.autoplayPolicy.countdownKey)
                                if (dismissedUpNextIdentity != dismissalIdentity) {
                                    UpNextCard(
                                        upNext = upNext,
                                        session = session,
                                        autoplayPolicy = playerContent.autoplayPolicy,
                                        countdownStarted = playbackState.status == PlaybackStatus.Completed,
                                        onPlayNext = { auto, generation ->
                                            // Keep controls when the queue switch is refused.
                                            val accepted = onPlayNext(auto, generation)
                                            if (accepted) {
                                                playNextRequested = true
                                                controlsVisible = false
                                            }
                                            accepted
                                        },
                                        onDismiss = { dismissedUpNextIdentity = dismissalIdentity },
                                        modifier =
                                            Modifier
                                                .align(Alignment.End)
                                                .windowInsetsPadding(
                                                    WindowInsets.safeDrawing.only(
                                                        if (showPlayerControls) {
                                                            WindowInsetsSides.Horizontal
                                                        } else {
                                                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                                                        },
                                                    ),
                                                ).padding(
                                                    start = Dimensions.screenPadding,
                                                    end = Dimensions.screenPadding,
                                                    bottom =
                                                        if (showPlayerControls) {
                                                            Dimensions.contentSpacing
                                                        } else {
                                                            Dimensions.screenPadding
                                                        },
                                                ),
                                    )
                                }
                            }
                        }
                        AnimatedVisibility(
                            visible = showPlayerControls,
                            enter =
                                if (platformCapabilities.desktopPlayerControls) {
                                    EnterTransition.None
                                } else {
                                    slideInVertically { height -> height } + fadeIn()
                                },
                            exit =
                                if (platformCapabilities.desktopPlayerControls) {
                                    ExitTransition.None
                                } else {
                                    slideOutVertically { height -> height } + fadeOut()
                                },
                        ) {
                            PlayerControls(
                                content = playerContent,
                                playbackStateFlow = playbackStateFlow,
                                session = session,
                                keyboardSeekTargetMs = holdSeek.pendingTargetMs,
                                onPointerScrubActiveChange = { active ->
                                    pointerScrubActive = active
                                    // Pointer scrubbing cancels an uncommitted keyboard seek.
                                    if (active) {
                                        holdSeek.cancel()
                                    }
                                },
                                onPlay = onPlay,
                                onPause = onPause,
                                onSetVolume = onSetVolume,
                                onToggleMute = onToggleMute,
                                onSeekTo = onSeekTo,
                                onShowPicker = onShowPicker,
                                onKeepControlsAlive = keepControlsAlive,
                                onControlsScrollActiveChange = { active -> controlsScrollActive = active },
                                onCycleResizeMode = onCycleResizeMode,
                                debugOverlayVisible = debugOverlayVisible,
                                onToggleDebugOverlay = {
                                    keepControlsAlive()
                                    debugOverlayVisible = !debugOverlayVisible
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.verticalGradient(
                                                0f to Color.Transparent,
                                                1f to Color.Black.copy(alpha = PLAYER_BOTTOM_SCRIM_ALPHA),
                                            ),
                                        ).windowInsetsPadding(
                                            WindowInsets.safeDrawing.only(
                                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                            ),
                                        ).padding(
                                            start = Dimensions.screenPadding,
                                            end = Dimensions.screenPadding,
                                            top = Dimensions.contentSpacing,
                                            bottom = Dimensions.formSpacing,
                                        ),
                            )
                        }
                    }
                    if (!isInPictureInPicture && playerContent.pickerVisible != PlayerPicker.None) {
                        PlayerPickerSheet(
                            content = playerContent,
                            playbackStateFlow = playbackStateFlow,
                            onHidePicker = {
                                onHidePicker()
                                controlsVisible = true
                            },
                            onSelectBackend = onSelectBackend,
                            onSeekTo = onSeekTo,
                            onSelectAudio = onSelectAudio,
                            onAdjustAudioTiming = onAdjustAudioTiming,
                            onResetAudioTiming = onResetAudioTiming,
                            onSelectSubtitle = onSelectSubtitle,
                            onSelectLocalSubtitle = onSelectLocalSubtitle,
                            onSelectOfflineSidecar = onSelectOfflineSidecar,
                            onAdjustSubtitleTiming = onAdjustSubtitleTiming,
                            onResetSubtitleTiming = onResetSubtitleTiming,
                            onSelectQuality = onSelectQuality,
                            onSelectQualityPolicy = onSelectQualityPolicy,
                            onClearQualityOverride = onClearQualityOverride,
                            onSetPlaybackSpeed = onSetPlaybackSpeed,
                            onSetSubtitleStyle = onSetSubtitleStyle,
                            onSetResizeMode = onSetResizeMode,
                            onPlayQueueItem = onPlayQueueItem,
                            onShuffleQueue = onShuffleQueue,
                            onKeepControlsAlive = keepControlsAlive,
                            onShowPicker = onShowPicker,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .windowInsetsPadding(WindowInsets.safeDrawing)
                                    .fillMaxWidth()
                                    .padding(Dimensions.screenPadding)
                                    .zIndex(PlayerOverlayLayer.MODAL),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControlsOverlay(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier,
        content = content,
    )
}

/** Plain holder used to suppress unchanged layout publications. */
private class PlayerSourceBoundsHolder {
    var value: Rect? = null
}
