// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.zIndex
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.player.BACKEND_FALLBACK_NOTICE_MS
import com.jellyscope.ui.screen.player.HoldSeekController
import com.jellyscope.ui.screen.player.HoldSeekDirection
import com.jellyscope.ui.screen.player.PlayerOverlayLayer
import com.jellyscope.ui.screen.player.PlayerPicker
import com.jellyscope.ui.screen.player.PlayerResizeMode
import com.jellyscope.ui.screen.player.PlayerUiState
import com.jellyscope.ui.screen.player.UpNextDismissalIdentity
import com.jellyscope.ui.screen.player.dismissalIdentity
import com.jellyscope.ui.screen.player.hasSubtitlePickerChoice
import com.jellyscope.ui.screen.player.holdSeekDirectionForKey
import com.jellyscope.ui.screen.player.parentPicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner

internal enum class TvPlayerBackAction {
    CloseLocalMenu,
    ClosePicker,
    DismissUpNext,
    HideOverlay,
    ExitPlayer,
}

internal enum class TvPlayerTransportAction {
    Play,
    Pause,
    Toggle,
    Next,
    Previous,
    Stop,
}

internal fun tvPlayerTransportActionForKey(key: Key): TvPlayerTransportAction? =
    when (key) {
        Key.MediaPlay -> TvPlayerTransportAction.Play
        Key.MediaPause -> TvPlayerTransportAction.Pause
        Key.MediaPlayPause -> TvPlayerTransportAction.Toggle
        Key.MediaNext -> TvPlayerTransportAction.Next
        Key.MediaPrevious -> TvPlayerTransportAction.Previous
        Key.MediaStop -> TvPlayerTransportAction.Stop
        else -> null
    }

internal fun tvPlayerBackAction(
    localMenuOpen: Boolean,
    pickerOpen: Boolean,
    upNextVisible: Boolean,
    controlsVisible: Boolean,
    queueOpen: Boolean,
): TvPlayerBackAction =
    when {
        localMenuOpen -> TvPlayerBackAction.CloseLocalMenu
        pickerOpen -> TvPlayerBackAction.ClosePicker
        upNextVisible -> TvPlayerBackAction.DismissUpNext
        controlsVisible || queueOpen -> TvPlayerBackAction.HideOverlay
        else -> TvPlayerBackAction.ExitPlayer
    }

@Composable
internal fun TvPlayerContent(
    session: Session,
    initialItemId: String,
    state: PlayerUiState,
    playbackStateFlow: StateFlow<PlaybackState>,
    controller: com.jellyscope.core.domain.playback.PlayerController,
    runtimeDiagnosticsFlow: StateFlow<PlaybackRuntimeDiagnostics> = controller.runtimeDiagnostics,
    // Synchronous identity prevents held seeks from crossing queue switches.
    playbackItemId: () -> String?,
    startWithPlaybackInfoOverlay: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onStop: () -> Unit = {},
    onShowPicker: (PlayerPicker) -> Unit,
    onHidePicker: () -> Unit,
    onSelectAudio: (Int) -> Unit,
    onAdjustAudioTiming: (Long) -> Unit = {},
    onResetAudioTiming: () -> Unit = {},
    onSelectSubtitle: (Int?) -> Unit,
    onSelectLocalSubtitle: (String) -> Unit = {},
    onAdjustSubtitleTiming: (Long) -> Unit = {},
    onResetSubtitleTiming: () -> Unit = {},
    onSelectQuality: (Long?) -> Unit,
    onSelectQualityPolicy: (
        com.jellyscope.core.domain.playback.PlaybackQualityPolicy,
    ) -> Unit = { policy -> onSelectQuality(policy.maxBitrateBps) },
    onClearQualityOverride: () -> Unit = {},
    onPlaybackAction: (PlaybackAction) -> Unit = {},
    onOpenPlaybackSettings: () -> Unit = {},
    onPlayQueueItem: (Int) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipCurrentSegment: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetSubtitleStyle: (SubtitleStyle) -> Unit,
    onSelectResizeMode: (PlayerResizeMode) -> Unit,
    onPlayNext: (Boolean, Long?) -> Boolean,
    onConfirmStillWatching: () -> Unit,
    onDismissPlaybackGuidance: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var lastPickerOpened by remember { mutableStateOf<PlayerPicker?>(null) }
    var localMenuVisible by remember { mutableStateOf(TvPlayerLocalMenu.None) }
    var lastLocalMenuOpened by remember { mutableStateOf<TvPlayerLocalMenu?>(null) }
    var debugOverlayVisible by remember { mutableStateOf(startWithPlaybackInfoOverlay) }
    var revealedBySeek by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var playNextRequested by remember { mutableStateOf(false) }
    var dismissedUpNextIdentity by remember { mutableStateOf<UpNextDismissalIdentity?>(null) }
    var audioNoticeVisible by remember { mutableStateOf(false) }
    var subtitleNoticeVisible by remember { mutableStateOf(false) }
    var backendNoticeVisible by remember { mutableStateOf(false) }
    val playerRequester = remember { FocusRequester() }
    val playFocusRequester = remember { FocusRequester() }
    val autoHideScope = rememberCoroutineScope()
    val autoHideJob = remember { AutoHideJobRef() }
    // Notice focus handoff must not replace the auto-hide job.
    val noticeFocusJob = remember { AutoHideJobRef() }
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentPlaybackItemId by rememberUpdatedState(playbackItemId)
    val holdSeek =
        remember {
            HoldSeekController(
                scope = autoHideScope,
                itemId = { currentPlaybackItemId() },
                positionMs = { playbackStateFlow.value.positionMs },
                durationMs = { playbackStateFlow.value.durationMs },
                commit = { target -> currentOnSeekTo(target) },
            )
        }
    // Stable identity keeps hold-seek updates out of the overlay.
    val pendingSeekTargetProvider = remember(holdSeek) { { holdSeek.pendingTargetMs } }
    val content = state as? PlayerUiState.Content
    val playbackStatus = content?.playbackState?.status ?: PlaybackStatus.Loading
    val pickerOpen = content != null && content.pickerVisible != PlayerPicker.None
    val modalOpen = pickerOpen || localMenuVisible != TvPlayerLocalMenu.None
    val upNextIdentity =
        content?.let { playerContent ->
            playerContent.upNext?.dismissalIdentity(playerContent.autoplayPolicy.countdownKey)
        }
    val upNextCardVisible =
        content?.upNext != null &&
            dismissedUpNextIdentity != upNextIdentity &&
            !modalOpen
    val visibleSkipAction =
        content
            ?.skipPromptSegment
            ?.skipAction()
            ?.takeUnless { modalOpen }
    val skipButtonVisible =
        visibleSkipAction != null
    val currentItemId = content?.currentItemId(initialItemId) ?: initialItemId
    // Null identity rejects holds during a queue switch.
    val holdSeekReady = content?.playbackItemId != null

    fun dismissUpNextCard() {
        upNextIdentity?.let { identity -> dismissedUpNextIdentity = identity }
    }

    fun restartAutoHideTimer() {
        autoHideJob.job?.cancel()
        autoHideJob.job = null
        if (
            controlsVisible &&
            !modalOpen &&
            playbackStatus == PlaybackStatus.Playing
        ) {
            autoHideJob.job =
                autoHideScope.launch {
                    delay(PLAYER_OVERLAY_HIDE_MS)
                    controlsVisible = false
                    queueOpen = false
                }
        }
    }

    fun requestPlayFocusThen(afterFocus: () -> Unit) {
        controlsVisible = true
        queueOpen = false
        // Re-arm auto-hide after focus handoff.
        autoHideJob.job?.cancel()
        autoHideJob.job = null
        noticeFocusJob.job?.cancel()
        noticeFocusJob.job =
            autoHideScope.launch {
                delay(PLAYER_NOTICE_FOCUS_HANDOFF_DELAY_MS)
                val focusRequester =
                    if (modalOpen) {
                        playerRequester
                    } else {
                        playFocusRequester
                    }
                // Focus the successor before dismissal removes the notice.
                focusRequester.requestFocusSafely()
                afterFocus()
                restartAutoHideTimer()
            }
    }

    fun dismissFocusedUpNextCard() {
        requestPlayFocusThen(::dismissUpNextCard)
    }

    val overlayShown = controlsVisible && !modalOpen

    fun handleBack() {
        when (
            tvPlayerBackAction(
                localMenuOpen = localMenuVisible != TvPlayerLocalMenu.None,
                pickerOpen = pickerOpen,
                upNextVisible = upNextCardVisible,
                controlsVisible = controlsVisible,
                queueOpen = queueOpen,
            )
        ) {
            TvPlayerBackAction.CloseLocalMenu -> localMenuVisible = TvPlayerLocalMenu.None
            TvPlayerBackAction.ClosePicker -> {
                // Offset panels return to their track picker.
                val parent = (content?.pickerVisible ?: PlayerPicker.None).parentPicker()
                if (parent != PlayerPicker.None) onShowPicker(parent) else onHidePicker()
            }
            TvPlayerBackAction.DismissUpNext -> dismissFocusedUpNextCard()
            TvPlayerBackAction.HideOverlay -> {
                controlsVisible = false
                queueOpen = false
            }
            TvPlayerBackAction.ExitPlayer -> onBack()
        }
    }

    BackHandler { handleBack() }

    LaunchedEffect(Unit) {
        playerRequester.requestFocusSafely()
    }

    DisposableEffect(Unit) {
        onDispose {
            autoHideJob.job?.cancel()
            noticeFocusJob.job?.cancel()
            holdSeek.cancel()
        }
    }

    // Item identity changes cancel pending holds.
    LaunchedEffect(content?.playbackItemId) {
        holdSeek.cancel()
    }

    LaunchedEffect(controlsVisible, playbackStatus, modalOpen) {
        restartAutoHideTimer()
    }
    LaunchedEffect(playbackStatus) {
        if (playbackStatus == PlaybackStatus.Playing) {
            playNextRequested = false
        } else if (!playNextRequested) {
            controlsVisible = true
        }
    }

    LaunchedEffect(content?.audioUnavailable) {
        if (content?.audioUnavailable == true) {
            audioNoticeVisible = true
            delay(AUDIO_UNAVAILABLE_NOTICE_MS)
            audioNoticeVisible = false
        } else {
            audioNoticeVisible = false
        }
    }
    LaunchedEffect(
        content?.playbackState?.subtitleActivation,
        content?.subtitleRenderInfo?.status,
        content?.subtitleRenderInfo?.streamIndex,
    ) {
        if (content?.subtitleRenderInfo?.status == SubtitleRenderStatus.Unavailable) {
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
    LaunchedEffect(content?.subtitleOptions?.size, content?.chapters?.size, content?.subtitleRenderInfo?.styleable, localMenuVisible) {
        if (localMenuVisible == TvPlayerLocalMenu.SubtitleStyle &&
            content?.subtitleRenderInfo?.styleable != true
        ) {
            localMenuVisible = TvPlayerLocalMenu.None
        }
        if (localMenuVisible == TvPlayerLocalMenu.Chapters &&
            content?.chapters?.isEmpty() != false
        ) {
            localMenuVisible = TvPlayerLocalMenu.None
        }
    }
    // Re-evaluate when either remote or local subtitle choices change.
    LaunchedEffect(
        content?.pickerVisible,
        content?.subtitleOptions?.size,
        content?.localSubtitleOptions?.size,
    ) {
        val hasSubtitleChoice = content?.hasSubtitlePickerChoice() == true
        if (content?.pickerVisible == PlayerPicker.Subtitles &&
            !hasSubtitleChoice
        ) {
            onHidePicker()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(playerRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // A seek key-up is the hold session's final commit.
                    if (event.type == KeyEventType.KeyUp) {
                        val isSeekKey = holdSeekDirectionForKey(event.key, includeDpadHorizontal = true) != null
                        return@onPreviewKeyEvent if (isSeekKey && holdSeek.sessionActive) {
                            holdSeek.onSeekKeyUp()
                            true
                        } else {
                            false
                        }
                    }
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }

                    // Keep the overlay visible during D-pad navigation.
                    restartAutoHideTimer()

                    // Root transport keys work regardless of the focused modal.
                    val transportAction = tvPlayerTransportActionForKey(event.key)
                    if (transportAction != null) {
                        holdSeek.commitBeforeAction()
                        when (transportAction) {
                            TvPlayerTransportAction.Play -> onPlay()
                            TvPlayerTransportAction.Pause -> onPause()
                            TvPlayerTransportAction.Toggle -> onTogglePlayPause()
                            TvPlayerTransportAction.Next -> {
                                playNextRequested = true
                                onNext()
                            }
                            TvPlayerTransportAction.Previous -> onPrevious()
                            TvPlayerTransportAction.Stop -> onStop()
                        }
                        return@onPreviewKeyEvent true
                    }

                    // Open pickers and menus own the remaining keys.
                    if (modalOpen) {
                        return@onPreviewKeyEvent false
                    }

                    if (upNextCardVisible) {
                        return@onPreviewKeyEvent when (event.key) {
                            Key.Back -> {
                                holdSeek.cancel()
                                dismissFocusedUpNextCard()
                                true
                            }
                            Key.Spacebar,
                            -> {
                                holdSeek.commitBeforeAction()
                                onTogglePlayPause()
                                true
                            }
                            Key.MediaRewind,
                            Key.MediaSkipBackward,
                            -> {
                                // Up Next has no scrub surface, so seek immediately.
                                if (holdSeekReady) {
                                    holdSeek.onSeekKeyDown(HoldSeekDirection.Backward)
                                    holdSeek.onSeekKeyUp()
                                }
                                true
                            }
                            Key.MediaFastForward,
                            Key.MediaSkipForward,
                            -> {
                                if (holdSeekReady) {
                                    holdSeek.onSeekKeyDown(HoldSeekDirection.Forward)
                                    holdSeek.onSeekKeyUp()
                                }
                                true
                            }
                            Key.DirectionCenter,
                            Key.Enter,
                            -> {
                                // Commit a carried hold before Up Next handles SELECT.
                                if (holdSeek.sessionActive) {
                                    holdSeek.commitBeforeAction()
                                }
                                false
                            }
                            else -> false
                        }
                    }

                    if (!overlayShown) {
                        // Reopen on the controls page.
                        controlsVisible = true
                        revealedBySeek = false
                        queueOpen = false
                        when (event.key) {
                            Key.DirectionCenter,
                            Key.Enter,
                            Key.Spacebar,
                            -> {
                                holdSeek.commitBeforeAction()
                                if (skipButtonVisible) {
                                    onSkipCurrentSegment()
                                } else {
                                    onTogglePlayPause()
                                }
                                true
                            }
                            // Skip keys reveal and retain scrub focus.
                            Key.DirectionLeft,
                            Key.MediaRewind,
                            Key.MediaSkipBackward,
                            -> {
                                revealedBySeek = true
                                if (holdSeekReady) {
                                    holdSeek.onSeekKeyDown(HoldSeekDirection.Backward)
                                }
                                true
                            }
                            Key.DirectionRight,
                            Key.MediaFastForward,
                            Key.MediaSkipForward,
                            -> {
                                revealedBySeek = true
                                if (holdSeekReady) {
                                    holdSeek.onSeekKeyDown(HoldSeekDirection.Forward)
                                }
                                true
                            }
                            Key.Back -> {
                                holdSeek.cancel()
                                onBack()
                                true
                            }
                            else -> false
                        }
                    } else if ((event.key == Key.DirectionCenter || event.key == Key.Enter) && holdSeek.sessionActive) {
                        // Commit a stale hold before SELECT reaches the child.
                        holdSeek.commitBeforeAction()
                        false
                    } else if (event.key == Key.Back) {
                        holdSeek.cancel()
                        handleBack()
                        true
                    } else if (event.key == Key.Spacebar) {
                        holdSeek.commitBeforeAction()
                        onTogglePlayPause()
                        true
                    } else if (event.key == Key.MediaRewind || event.key == Key.MediaSkipBackward) {
                        if (holdSeekReady) {
                            holdSeek.onSeekKeyDown(HoldSeekDirection.Backward)
                        }
                        true
                    } else if (event.key == Key.MediaFastForward || event.key == Key.MediaSkipForward) {
                        if (holdSeekReady) {
                            holdSeek.onSeekKeyDown(HoldSeekDirection.Forward)
                        }
                        true
                    } else {
                        false
                    }
                },
    ) {
        TvPlayerSurface(
            controller = controller,
            modifier =
                Modifier
                    .fillMaxSize()
                    .zIndex(PlayerOverlayLayer.VIDEO),
            subtitlesRaised = overlayShown,
            resizeMode = content?.resizeMode ?: PlayerResizeMode.Fit,
            subtitleStyle =
                content?.subtitleStyle ?: com.jellyscope.core.domain.playback
                    .SubtitleStyle(),
        )

        when (state) {
            PlayerUiState.Loading ->
                TvSpinner(modifier = Modifier.align(Alignment.Center))
            is PlayerUiState.Error ->
                TvPlayerError(
                    retryable = state.retryable,
                    error = state.error,
                    onRetry = onRetry,
                    onCancel = onBack,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .zIndex(PlayerOverlayLayer.MODAL),
                )
            is PlayerUiState.Content -> {
                if (playbackStatus == PlaybackStatus.Loading || playbackStatus == PlaybackStatus.Buffering) {
                    TvSpinner(modifier = Modifier.align(Alignment.Center))
                }
                // Notices and modals stay above the debug panel.
                if (debugOverlayVisible) {
                    TvDebugInfoOverlay(
                        debugInfo = state.debugInfo,
                        playbackStateFlow = playbackStateFlow,
                        runtimeDiagnosticsFlow = runtimeDiagnosticsFlow,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .zIndex(PlayerOverlayLayer.DEBUG_OVERLAY),
                    )
                }
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = TvDimens.playerOverlayVerticalPadding)
                            .zIndex(PlayerOverlayLayer.POPUP),
                    verticalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedVisibility(
                        visible = state.audioUnavailable && audioNoticeVisible,
                        enter = slideInVertically { height -> -height } + fadeIn(),
                        exit = slideOutVertically { height -> -height } + fadeOut(),
                    ) {
                        TvAudioUnavailableBanner()
                    }
                    AnimatedVisibility(
                        visible = subtitleNoticeVisible,
                        enter = slideInVertically { height -> -height } + fadeIn(),
                        exit = slideOutVertically { height -> -height } + fadeOut(),
                    ) {
                        TvSubtitleUnavailableBanner()
                    }
                    AnimatedVisibility(
                        visible = backendNoticeVisible,
                        enter = slideInVertically { height -> -height } + fadeIn(),
                        exit = slideOutVertically { height -> -height } + fadeOut(),
                    ) {
                        state.backendNotice?.let { notice ->
                            TvBackendFallbackBanner(notice = notice)
                        }
                    }
                }
                val noticePlacement =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(PlayerOverlayLayer.POPUP)
                        .padding(
                            start = TvDimens.playerOverlayHorizontalPadding,
                            end = TvDimens.playerOverlayHorizontalPadding,
                            bottom =
                                if (overlayShown) {
                                    TvDimens.playerFloatingPanelBottomPadding
                                } else {
                                    TvDimens.playerOverlayVerticalPadding
                                },
                        )
                state.playbackActionNotice?.let { notice ->
                    TvPlaybackActionNotice(
                        notice = notice,
                        onAction = onPlaybackAction,
                        onFocusedDismiss = ::requestPlayFocusThen,
                        modifier = noticePlacement,
                    )
                } ?: state.playbackGuidance?.let { guidance ->
                    TvPlaybackGuidanceNotice(
                        guidance = guidance,
                        onDismiss = onDismissPlaybackGuidance,
                        onOpenPlaybackSettings = onOpenPlaybackSettings,
                        onFocusedDismiss = ::requestPlayFocusThen,
                        modifier = noticePlacement,
                    )
                }
                if (state.audioUnavailable) {
                    TvAudioUnavailableGlyph(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .zIndex(PlayerOverlayLayer.POPUP)
                                .padding(
                                    top = TvDimens.playerOverlayVerticalPadding,
                                    end = TvDimens.playerOverlayHorizontalPadding,
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
                    val upNext = state.upNext
                    if (upNextCardVisible && upNext != null) {
                        TvUpNextCard(
                            session = session,
                            upNext = upNext,
                            autoplayPolicy = state.autoplayPolicy,
                            stillWatchingPrompt = state.stillWatchingPrompt,
                            countdownStarted = playbackStatus == PlaybackStatus.Completed,
                            onPlayNext = { auto, generation ->
                                // Keep the overlay when the switch is refused.
                                val accepted = onPlayNext(auto, generation)
                                if (accepted) {
                                    playNextRequested = true
                                    controlsVisible = false
                                    queueOpen = false
                                }
                                accepted
                            },
                            onConfirmStillWatching = onConfirmStillWatching,
                            onDismiss = ::dismissFocusedUpNextCard,
                            modifier =
                                Modifier
                                    .align(Alignment.End)
                                    .padding(
                                        end = TvDimens.playerOverlayHorizontalPadding,
                                        bottom =
                                            if (overlayShown) {
                                                TvDimens.playerOverlayGap
                                            } else {
                                                TvDimens.playerOverlayVerticalPadding
                                            },
                                    ),
                        )
                    }
                    // Pickers replace the overlay; Up Next retains eligible focus.
                    if (overlayShown) {
                        val onQueueOpenChangedCallback = remember { { open: Boolean -> queueOpen = open } }
                        val onInitialFocusConsumedCallback =
                            remember {
                                {
                                    lastPickerOpened = null
                                    lastLocalMenuOpened = null
                                    revealedBySeek = false
                                }
                            }
                        val onSeekKeyDownCallback =
                            remember(holdSeek, holdSeekReady) {
                                { direction: HoldSeekDirection ->
                                    if (holdSeekReady) {
                                        holdSeek.onSeekKeyDown(direction)
                                    }
                                }
                            }
                        val onShowPickerCallback =
                            remember(onShowPicker) {
                                { picker: PlayerPicker ->
                                    lastPickerOpened = picker
                                    onShowPicker(picker)
                                }
                            }
                        val onShowLocalMenuCallback =
                            remember {
                                { menu: TvPlayerLocalMenu ->
                                    lastLocalMenuOpened = menu
                                    localMenuVisible = menu
                                }
                            }
                        val onToggleDebugOverlayCallback = remember { { debugOverlayVisible = !debugOverlayVisible } }

                        TvPlayerOverlay(
                            session = session,
                            content = state,
                            currentItemId = currentItemId,
                            playbackStateFlow = playbackStateFlow,
                            initialFocusPicker = lastPickerOpened,
                            initialFocusSeek = revealedBySeek,
                            requestInitialFocus = !upNextCardVisible,
                            queueOpen = queueOpen,
                            onQueueOpenChanged = onQueueOpenChangedCallback,
                            onInitialFocusConsumed = onInitialFocusConsumedCallback,
                            onTogglePlayPause = onTogglePlayPause,
                            playFocusRequester = playFocusRequester,
                            pendingSeekTargetMs = pendingSeekTargetProvider,
                            onSeekKeyDown = onSeekKeyDownCallback,
                            onShowPicker = onShowPickerCallback,
                            initialFocusMenu = lastLocalMenuOpened,
                            onShowLocalMenu = onShowLocalMenuCallback,
                            onToggleDebugOverlay = onToggleDebugOverlayCallback,
                            onSelectResizeMode = onSelectResizeMode,
                            onPlayQueueItem = onPlayQueueItem,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.35f to Color.Black.copy(alpha = 0.6f),
                                            1f to Color.Black.copy(alpha = 0.92f),
                                        ),
                                    ).padding(
                                        horizontal = TvDimens.playerOverlayHorizontalPadding,
                                        vertical = TvDimens.playerOverlayVerticalPadding,
                                    ),
                        )
                    }
                }
                if (overlayShown) {
                    OverlayMetadataRow(
                        metadata = state.metadata,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .zIndex(PlayerOverlayLayer.CHROME)
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.6f),
                                        1f to Color.Transparent,
                                    ),
                                ).padding(
                                    horizontal = TvDimens.playerOverlayHorizontalPadding,
                                    vertical = TvDimens.playerOverlayVerticalPadding,
                                ),
                    )
                }
                visibleSkipAction?.let { skipAction ->
                    TvSkipSegmentButton(
                        action = skipAction,
                        requestInitialFocus = !upNextCardVisible,
                        onClick = onSkipCurrentSegment,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .zIndex(PlayerOverlayLayer.CHROME)
                                .padding(
                                    end = TvDimens.playerOverlayHorizontalPadding,
                                    top = TvDimens.playerOverlayVerticalPadding,
                                ),
                    )
                }
                if (state.pickerVisible != PlayerPicker.None) {
                    TvPickerOverlay(
                        content = state,
                        onHidePicker = onHidePicker,
                        onSelectAudio = onSelectAudio,
                        onAdjustAudioTiming = onAdjustAudioTiming,
                        onResetAudioTiming = onResetAudioTiming,
                        onSelectSubtitle = onSelectSubtitle,
                        onSelectLocalSubtitle = onSelectLocalSubtitle,
                        onAdjustSubtitleTiming = onAdjustSubtitleTiming,
                        onResetSubtitleTiming = onResetSubtitleTiming,
                        onSelectQuality = onSelectQuality,
                        onSelectQualityPolicy = onSelectQualityPolicy,
                        onClearQualityOverride = onClearQualityOverride,
                        onShowPicker = { picker ->
                            lastPickerOpened = picker
                            onShowPicker(picker)
                        },
                        modifier = Modifier.zIndex(PlayerOverlayLayer.MODAL),
                    )
                }
                if (localMenuVisible != TvPlayerLocalMenu.None) {
                    TvPlayerLocalMenuOverlay(
                        content = state,
                        playbackStateFlow = playbackStateFlow,
                        menu = localMenuVisible,
                        onHideMenu = { localMenuVisible = TvPlayerLocalMenu.None },
                        onSeekTo = { positionMs ->
                            onSeekTo(positionMs)
                            localMenuVisible = TvPlayerLocalMenu.None
                        },
                        onSetPlaybackSpeed = onSetPlaybackSpeed,
                        onSetSubtitleStyle = onSetSubtitleStyle,
                        onSelectResizeMode = { mode ->
                            onSelectResizeMode(mode)
                            localMenuVisible = TvPlayerLocalMenu.None
                        },
                        modifier = Modifier.zIndex(PlayerOverlayLayer.MODAL),
                    )
                }
            }
        }
    }
}
