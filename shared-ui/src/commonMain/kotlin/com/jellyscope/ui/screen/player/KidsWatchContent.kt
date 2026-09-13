// SPDX-License-Identifier: MPL-2.0

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.jellyscope.ui.screen.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateInt
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.animateRect
import androidx.compose.animation.core.animateSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import com.jellyscope.ui.generated.resources.detail_retry
import com.jellyscope.ui.generated.resources.kids_downloads
import com.jellyscope.ui.generated.resources.kids_downloads_error
import com.jellyscope.ui.generated.resources.kids_empty
import com.jellyscope.ui.generated.resources.kids_more_playback_options
import com.jellyscope.ui.generated.resources.kids_recommendations
import com.jellyscope.ui.generated.resources.kids_replay
import com.jellyscope.ui.generated.resources.kids_retry_catalogue
import com.jellyscope.ui.generated.resources.kids_retry_offline
import com.jellyscope.ui.generated.resources.player_audio
import com.jellyscope.ui.generated.resources.player_fullscreen_enter_cd
import com.jellyscope.ui.generated.resources.player_fullscreen_exit_cd
import com.jellyscope.ui.generated.resources.player_pause_cd
import com.jellyscope.ui.generated.resources.player_play_cd
import com.jellyscope.ui.generated.resources.player_quality
import com.jellyscope.ui.generated.resources.player_subtitles
import com.jellyscope.ui.platform.LocalFullscreenToggle
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun KidsWatchContent(
    state: PlayerUiState,
    kidsState: KidsWatchUiState,
    session: Session,
    controller: PlayerController,
    playbackStateFlow: StateFlow<PlaybackState>,
    isInPictureInPicture: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onReplay: () -> Unit,
    onRetry: () -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    onHidePicker: () -> Unit,
    onSelectBackend: (PlayerBackend) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onAdjustAudioTiming: (Long) -> Unit,
    onResetAudioTiming: () -> Unit,
    onSelectSubtitle: (Int?) -> Unit,
    onSelectLocalSubtitle: (String) -> Unit,
    onSelectOfflineSidecar: () -> Unit,
    onAdjustSubtitleTiming: (Long) -> Unit,
    onResetSubtitleTiming: () -> Unit,
    onSelectQuality: (Long?) -> Unit,
    onSelectQualityPolicy: (PlaybackQualityPolicy) -> Unit,
    onClearQualityOverride: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetSubtitleStyle: (SubtitleStyle) -> Unit,
    onSetResizeMode: (PlayerResizeMode) -> Unit,
    onPlaybackAction: (PlaybackAction) -> Unit,
    onDismissPlaybackGuidance: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    onSelectAsset: (KidsWatchItem) -> Unit,
    onRetryCatalogue: () -> Unit,
    onRetryOffline: () -> Unit,
    onOpenDownloads: () -> Unit,
    onPictureInPictureModeChanged: (Boolean) -> Unit,
    playerSourceBounds: Rect?,
    onPlayerSourceBoundsChanged: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = state as? PlayerUiState.Content
    val fullscreenToggle = LocalFullscreenToggle.current
    val platformCapabilities = LocalPlatformCapabilities.current
    val recommendationScrollState = rememberSaveableStateHolder()
    var controlsVisible by remember { mutableStateOf(true) }
    var optionsExpanded by remember { mutableStateOf(false) }
    var audioNoticeVisible by remember { mutableStateOf(false) }
    var subtitleNoticeVisible by remember { mutableStateOf(false) }
    var backendNoticeVisible by remember { mutableStateOf(false) }
    val audioNoticeInactivity = remember { InactivitySignal() }
    var controlsHeightPx by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val subtitleClearance = with(density) { controlsHeightPx.toInt().coerceAtLeast(0).toDp() }
    val controlsInactivity = remember { InactivitySignal() }
    val playbackStatus = content?.playbackState?.status ?: PlaybackStatus.Loading
    var gestureHud by remember { mutableStateOf<PlayerGestureHud?>(null) }
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnPause by rememberUpdatedState(onPause)
    val currentOnTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val commandCallbacks =
        remember {
            PlayerPlatformCommandCallbacks(
                play = { currentOnPlay() },
                pause = { currentOnPause() },
                toggle = { currentOnTogglePlayPause() },
                seekTo = { positionMs -> currentOnSeekTo(positionMs) },
                next = {},
                previous = {},
                stop = onBack,
            )
        }

    fun closePlayer() = onBack()

    fun handleBack() {
        val picker = content?.pickerVisible ?: PlayerPicker.None
        when {
            picker.parentPicker() != PlayerPicker.None -> onShowPicker(picker.parentPicker())
            picker != PlayerPicker.None -> onHidePicker()
            fullscreenToggle.isFullscreen -> fullscreenToggle.exitFullscreen()
            else -> closePlayer()
        }
    }

    fun keepControlsAlive() {
        controlsVisible = true
        controlsInactivity.signal()
        if (content?.audioUnavailable == true) {
            audioNoticeVisible = true
            audioNoticeInactivity.signal()
        }
    }

    BackHandler(enabled = !isInPictureInPicture, onBack = ::handleBack)
    PlayerPlatformEffects(
        controller = controller,
        content = content,
        sourceBounds = playerSourceBounds,
        commandCallbacks = commandCallbacks,
        isFullscreen = fullscreenToggle.isFullscreen,
        onPictureInPictureModeChanged = onPictureInPictureModeChanged,
        onCloseFromPictureInPicture = ::closePlayer,
        onBackgrounded = ::closePlayer,
    )
    PlayerNowPlayingEffects(
        controller = controller,
        content = content,
        onPlay = onPlay,
        onPause = onPause,
        onToggle = commandCallbacks.toggle,
        onSeekTo = onSeekTo,
    )
    LaunchedEffect(controlsVisible, playbackStatus, content?.pickerVisible, isInPictureInPicture, optionsExpanded) {
        if (
            controlsVisible &&
            playbackStatus == PlaybackStatus.Playing &&
            content?.pickerVisible == PlayerPicker.None &&
            !optionsExpanded &&
            !isInPictureInPicture
        ) {
            controlsInactivity.signal()
            controlsInactivity.awaitInactivity(PLAYER_CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(playbackStatus, content?.pickerVisible) {
        if (playbackStatus != PlaybackStatus.Playing || content?.pickerVisible != PlayerPicker.None) {
            controlsVisible = true
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
        subtitleNoticeVisible = content?.subtitleNotice != null
        if (subtitleNoticeVisible) {
            delay(SUBTITLE_UNAVAILABLE_NOTICE_MS)
            subtitleNoticeVisible = false
        }
    }
    LaunchedEffect(content?.backendNotice?.token) {
        backendNoticeVisible = content?.backendNotice != null
        if (backendNoticeVisible) {
            delay(BACKEND_FALLBACK_NOTICE_MS)
            backendNoticeVisible = false
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(LocalAppBackgroundBrush.current).clipToBounds()) {
        val landscape = maxWidth > maxHeight
        val fullscreen = fullscreenToggle.isFullscreen || isInPictureInPicture
        val animateLayout = !isInPictureInPicture
        val layoutTransition =
            key(isInPictureInPicture) {
                updateTransition(KidsPlayerLayout(landscape, fullscreen), label = "Kids player layout")
            }
        val orientationChanged = layoutTransition.segment.initialState.landscape != layoutTransition.segment.targetState.landscape
        val layoutAnimationMs =
            if (orientationChanged) {
                PLAYER_ROTATION_ANIMATION_MS
            } else {
                PLAYER_LAYOUT_ANIMATION_MS
            }
        val fullscreenProgress =
            layoutTransition.animateFloat(
                transitionSpec = { if (animateLayout) tween(layoutAnimationMs) else snap() },
                label = "Kids fullscreen progress",
            ) { layout -> if (layout.fullscreen) 1f else 0f }
        val fullscreenVisible by remember(fullscreenProgress) {
            derivedStateOf { fullscreenProgress.value > 0f }
        }
        val reserveBottomInset = fullscreen || fullscreenVisible
        val safeTop = WindowInsets.safeDrawing.getTop(density)
        val safeBottom = WindowInsets.safeDrawing.getBottom(density)
        val topInset =
            layoutTransition.animateInt(
                transitionSpec = { if (animateLayout) tween(layoutAnimationMs) else snap() },
                label = "Kids fullscreen top inset",
            ) { layout -> if (layout.fullscreen) 0 else safeTop }
        val appliedTopInset =
            remember(topInset) {
                object : WindowInsets {
                    override fun getTop(density: Density): Int = topInset.value

                    override fun getBottom(density: Density): Int = 0

                    override fun getLeft(
                        density: Density,
                        layoutDirection: LayoutDirection,
                    ): Int = 0

                    override fun getRight(
                        density: Density,
                        layoutDirection: LayoutDirection,
                    ): Int = 0
                }
            }
        val titleReservePx =
            with(density) {
                MaterialTheme.typography.titleMedium.lineHeight
                    .toPx()
                    .roundToInt() +
                    (Dimensions.contentSpacing * 2).roundToPx()
            }
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val availableHeight = (heightPx - safeTop).coerceAtLeast(0)
        val preferredWidth = if (landscape) (widthPx * Dimensions.kidsLandscapePlayerFraction).roundToInt() else widthPx
        val titleFits =
            landscape &&
                availableHeight - safeBottom - (preferredWidth / Dimensions.videoCardAspectRatio).roundToInt() >= titleReservePx
        val bottomReserve = if (landscape) safeBottom + if (titleFits) titleReservePx else 0 else 0
        val inlineHeightLimit = (availableHeight - bottomReserve).coerceAtLeast(0)
        val inlineWidth = minOf(preferredWidth, (inlineHeightLimit * Dimensions.videoCardAspectRatio).roundToInt())
        val inlineHeight = (inlineWidth / Dimensions.videoCardAspectRatio).roundToInt()
        val fullscreenWidth = minOf(widthPx, (heightPx * Dimensions.videoCardAspectRatio).roundToInt())
        val fullscreenHeight = (fullscreenWidth / Dimensions.videoCardAspectRatio).roundToInt()
        val targetPlayerBounds =
            if (fullscreen) {
                Rect(
                    left = (widthPx - fullscreenWidth) / 2f,
                    top = (heightPx - fullscreenHeight) / 2f,
                    right = (widthPx + fullscreenWidth) / 2f,
                    bottom = (heightPx + fullscreenHeight) / 2f,
                )
            } else {
                Rect(0f, safeTop.toFloat(), inlineWidth.toFloat(), (safeTop + inlineHeight).toFloat())
            }
        val playerSize =
            layoutTransition.animateSize(
                transitionSpec = { if (animateLayout) tween(layoutAnimationMs) else snap() },
                label = "Kids video size",
            ) { targetPlayerBounds.size }
        // Rotation replaces the coordinate space; only the size animation survives it.
        val playerCenter =
            key(landscape, isInPictureInPicture) {
                animateOffsetAsState(
                    targetValue = targetPlayerBounds.center,
                    animationSpec = if (animateLayout) tween(PLAYER_LAYOUT_ANIMATION_MS) else snap(),
                    label = "Kids video center",
                )
            }
        val recommendationBounds =
            layoutTransition.animateRect(
                transitionSpec = { if (animateLayout) tween(layoutAnimationMs) else snap() },
                label = "Kids recommendation bounds",
            ) {
                if (landscape) {
                    val left = if (fullscreen) widthPx else inlineWidth
                    Rect(left.toFloat(), safeTop.toFloat(), (left + widthPx - inlineWidth).toFloat(), heightPx.toFloat())
                } else {
                    val top = if (fullscreen) heightPx else safeTop + inlineHeight
                    Rect(0f, top.toFloat(), widthPx.toFloat(), (top + availableHeight - inlineHeight).toFloat())
                }
            }
        val dragScope = rememberCoroutineScope()
        val fullscreenDrag = remember(widthPx, heightPx, isInPictureInPicture) { KidsFullscreenDragState(dragScope) }
        DisposableEffect(fullscreenDrag) {
            onDispose { fullscreenDrag.cancel() }
        }
        Layout(
            content = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .then(
                                if (content != null && !isInPictureInPicture) {
                                    Modifier
                                } else {
                                    Modifier.pointerInput(content, controlsVisible, isInPictureInPicture) {
                                        detectTapGestures(
                                            onTap = {
                                                if (!isInPictureInPicture) {
                                                    controlsVisible = !controlsVisible
                                                    if (controlsVisible) keepControlsAlive()
                                                }
                                            },
                                        )
                                    }
                                },
                            ),
                ) {
                    PlayerSurface(
                        controller = controller,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { coordinates -> onPlayerSourceBoundsChanged(coordinates.boundsInWindow()) },
                        resizeMode = PlayerResizeMode.Fit,
                        subtitleStyle = content?.subtitleStyle ?: SubtitleStyle(),
                        subtitleClearanceActive = controlsVisible && content != null && !isInPictureInPicture,
                        subtitleBottomClearance = if (controlsVisible && !isInPictureInPicture) subtitleClearance else 0.dp,
                        pictureInPictureRequiresLinearPlayback = content?.pictureInPictureRequiresLinearPlayback == true,
                    )
                    if (content != null && !isInPictureInPicture) {
                        key(widthPx, heightPx, fullscreenToggle.isFullscreen) {
                            val dragDirection =
                                if (fullscreenToggle.isFullscreen) PlayerFullscreenDragDirection.Down else PlayerFullscreenDragDirection.Up
                            PlayerGestureLayer(
                                content = content,
                                playbackState = { playbackStateFlow.value },
                                onToggleControls = {
                                    controlsVisible = !controlsVisible
                                    if (controlsVisible) keepControlsAlive()
                                },
                                onToggleFullscreen = fullscreenToggle.toggleFullscreen,
                                onSeekTo = onSeekTo,
                                onSetPlaybackSpeed = onSetPlaybackSpeed,
                                onHud = { hud -> gestureHud = hud },
                                volumeAndBrightnessEnabled = false,
                                playbackGesturesEnabled = fullscreenToggle.isFullscreen,
                                fullscreenDragDirection = dragDirection,
                                onFullscreenDrag = { distance, videoHeightPx ->
                                    fullscreenDrag.dragTo(
                                        distance = distance,
                                        maximumPx = heightPx * KIDS_FULLSCREEN_DRAG_MAX_FRACTION,
                                        direction = dragDirection,
                                        thresholdPx = videoHeightPx * KIDS_FULLSCREEN_DRAG_THRESHOLD_FRACTION,
                                    )
                                },
                                onFullscreenDragEnd = { cancelled ->
                                    fullscreenDrag.finish(
                                        cancelled = cancelled,
                                        direction = dragDirection,
                                        onCommit = fullscreenToggle.toggleFullscreen,
                                    )
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    when (state) {
                        PlayerUiState.Loading -> BufferingIndicator(compact = true, modifier = Modifier.align(Alignment.Center))
                        is PlayerUiState.Error ->
                            PlayerError(
                                retryable = state.retryable,
                                error = state.error,
                                onBack = ::closePlayer,
                                onRetry = onRetry,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        is PlayerUiState.Content -> Unit
                    }
                    if (!isInPictureInPicture && (controlsVisible || content == null)) {
                        KidsNavigationControls(
                            onBack = ::handleBack,
                            content = content,
                            optionsExpanded = optionsExpanded,
                            onOptionsExpandedChange = { expanded ->
                                optionsExpanded = expanded
                                keepControlsAlive()
                            },
                            onShowPicker = onShowPicker,
                            onKeepControlsAlive = ::keepControlsAlive,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                    if (content != null && !isInPictureInPicture) {
                        KidsPlayPauseControl(
                            playbackStateFlow = playbackStateFlow,
                            controlsVisible = controlsVisible,
                            onPlay = onPlay,
                            onPause = onPause,
                            onReplay = onReplay,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (content != null && controlsVisible && !isInPictureInPicture) {
                        KidsPlayerControls(
                            content = content,
                            session = session,
                            playbackStateFlow = playbackStateFlow,
                            onSeekTo = onSeekTo,
                            onToggleFullscreen = fullscreenToggle.toggleFullscreen,
                            fullscreen = fullscreenToggle.isFullscreen,
                            reserveBottomInset = reserveBottomInset,
                            fullscreenAvailable = platformCapabilities.playerFullscreenControl,
                            onKeepControlsAlive = ::keepControlsAlive,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .onGloballyPositioned { coordinates -> controlsHeightPx = coordinates.size.height.toLong() },
                        )
                    }
                    if (content != null && !isInPictureInPicture && content.pickerVisible != PlayerPicker.None) {
                        PlayerPickerSheet(
                            content = content,
                            playbackStateFlow = playbackStateFlow,
                            onHidePicker = onHidePicker,
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
                            onPlayQueueItem = {},
                            onShuffleQueue = {},
                            onKeepControlsAlive = ::keepControlsAlive,
                            onShowPicker = onShowPicker,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(Dimensions.screenPadding),
                        )
                    }
                    if (content != null && !isInPictureInPicture) {
                        Column(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            if (reserveBottomInset) {
                                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                                            } else {
                                                WindowInsetsSides.Horizontal
                                            },
                                        ),
                                    ).padding(
                                        start = Dimensions.contentSpacing,
                                        end = Dimensions.contentSpacing,
                                        bottom = if (controlsVisible) subtitleClearance else Dimensions.contentSpacing,
                                    ),
                            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
                        ) {
                            if (backendNoticeVisible) {
                                content.backendNotice?.let { BackendFallbackBanner(notice = it) }
                            }
                            if (subtitleNoticeVisible) {
                                SubtitleUnavailableBanner(message = content.subtitleNotice?.message)
                            }
                            if (content.audioUnavailable && audioNoticeVisible) {
                                AudioUnavailableBanner(onDismiss = { audioNoticeVisible = false })
                            } else if (content.audioUnavailable && !controlsVisible) {
                                AudioUnavailableGlyph()
                            }
                        }
                    }
                    if (content?.playbackChangeNotice != null && !isInPictureInPicture) {
                        PlaybackChangeKeptBanner(
                            notice = content.playbackChangeNotice,
                            onDismiss = { onPlaybackAction(PlaybackAction.Dismiss) },
                            modifier = Modifier.align(Alignment.TopCenter).padding(Dimensions.screenPadding),
                        )
                    } else if (content?.playbackActionNotice != null && !isInPictureInPicture) {
                        PlaybackActionNoticeBanner(
                            notice = content.playbackActionNotice,
                            onAction = onPlaybackAction,
                            modifier = Modifier.align(Alignment.TopCenter).padding(Dimensions.screenPadding),
                        )
                    } else if (content?.playbackGuidance != null && !isInPictureInPicture) {
                        PlaybackGuidanceBanner(
                            guidance = content.playbackGuidance,
                            onReduce = { content.playbackGuidance.nextLowerQualityRungBps?.let(onSelectQuality) },
                            onOpenPlaybackSettings = onOpenPlaybackSettings,
                            onDismiss = onDismissPlaybackGuidance,
                            modifier = Modifier.align(Alignment.TopCenter).padding(Dimensions.screenPadding),
                        )
                    }
                    if (gestureHud != null && !isInPictureInPicture) {
                        GestureHud(
                            hud = gestureHud,
                            modifier = Modifier.align(Alignment.Center).padding(Dimensions.screenPadding),
                        )
                    }
                }
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        recommendationScrollState.SaveableStateProvider(if (landscape) "landscape" else "portrait") {
                            KidsRecommendations(
                                playingTitle = if (landscape) "" else content?.metadata?.title.orEmpty(),
                                landscape = landscape,
                                state = kidsState,
                                session = session,
                                onSelectAsset = onSelectAsset,
                                onRetryCatalogue = onRetryCatalogue,
                                onRetryOffline = onRetryOffline,
                                onOpenDownloads = onOpenDownloads,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Box {
                    Text(
                        text = content?.metadata?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(Dimensions.contentSpacing),
                    )
                }
            },
            modifier = Modifier.fillMaxSize().consumeWindowInsets(appliedTopInset),
        ) { measurables, constraints ->
            val player = measurables[0]
            val recommendations = measurables[1]
            val title = measurables[2]
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val videoSize = if (isInPictureInPicture) targetPlayerBounds.size else playerSize.value
            val videoCenter = if (isInPictureInPicture) targetPlayerBounds.center else playerCenter.value
            val video = Rect(videoCenter - Offset(videoSize.width / 2f, videoSize.height / 2f), videoSize)
            val videoWidth = video.width.roundToInt().coerceAtLeast(0)
            val videoHeight = video.height.roundToInt().coerceAtLeast(0)
            val playerPlaceable = player.measure(Constraints.fixed(videoWidth, videoHeight))
            val recommendationRect = recommendationBounds.value
            val recommendationsPlaceable =
                recommendations.measure(
                    Constraints.fixed(
                        recommendationRect.width.roundToInt().coerceAtLeast(0),
                        recommendationRect.height.roundToInt().coerceAtLeast(0),
                    ),
                )
            val titlePlaceable =
                title.measure(
                    if (titleFits) {
                        Constraints(maxWidth = inlineWidth, maxHeight = (availableHeight - inlineHeight).coerceAtLeast(0))
                    } else {
                        Constraints.fixed(0, 0)
                    },
                )
            layout(width, height) {
                val progress = fullscreenProgress.value.coerceIn(0f, 1f)
                val videoX = video.left.roundToInt()
                val videoY = video.top.roundToInt()
                playerPlaceable.place(videoX, videoY + fullscreenDrag.offsetPx.roundToInt(), zIndex = 1f)
                if (!isInPictureInPicture && progress < 1f) {
                    if (titleFits) {
                        titlePlaceable.placeWithLayer(videoX, videoY + videoHeight) { alpha = 1f - progress }
                    }
                    recommendationsPlaceable.placeWithLayer(
                        x = recommendationRect.left.roundToInt(),
                        y = recommendationRect.top.roundToInt(),
                    ) { alpha = 1f - progress }
                }
            }
        }
    }
}

@Composable
private fun KidsNavigationControls(
    onBack: () -> Unit,
    content: PlayerUiState.Content?,
    optionsExpanded: Boolean,
    onOptionsExpandedChange: (Boolean) -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    onKeepControlsAlive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ).padding(horizontal = Dimensions.contentSpacing),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KidsControlButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.detail_back), onBack)
        if (content != null) {
            KidsPlaybackOptions(content, optionsExpanded, onOptionsExpandedChange, onShowPicker, onKeepControlsAlive)
        }
    }
}

@Composable
private fun KidsPlayerControls(
    content: PlayerUiState.Content,
    session: Session,
    playbackStateFlow: StateFlow<PlaybackState>,
    onSeekTo: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
    fullscreen: Boolean,
    reserveBottomInset: Boolean,
    fullscreenAvailable: Boolean,
    onKeepControlsAlive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        if (reserveBottomInset) WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal,
                    ),
                ).padding(horizontal = Dimensions.contentSpacing),
    ) {
        PlayerSeekSection(
            content = content,
            playbackStateFlow = playbackStateFlow,
            session = session,
            keyboardSeekTargetMs = null,
            onSeekTo = onSeekTo,
            onKeepControlsAlive = onKeepControlsAlive,
            onPointerScrubActiveChange = {},
            contentColor = Color.White,
            compact = true,
            trailingTimeControl = {
                if (fullscreenAvailable) {
                    KidsControlButton(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        stringResource(if (fullscreen) Res.string.player_fullscreen_exit_cd else Res.string.player_fullscreen_enter_cd),
                        onToggleFullscreen,
                    )
                }
            },
        )
    }
}

@Composable
private fun KidsPlayPauseControl(
    playbackStateFlow: StateFlow<PlaybackState>,
    controlsVisible: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusFlow = remember(playbackStateFlow) { playbackStateFlow.map { it.status }.distinctUntilChanged() }
    val status by statusFlow.collectAsStateWithLifecycle(playbackStateFlow.value.status)
    val replay = status == PlaybackStatus.Completed
    val playing = status == PlaybackStatus.Playing
    Box(modifier = modifier) {
        when (status) {
            PlaybackStatus.Idle,
            PlaybackStatus.Loading,
            PlaybackStatus.Buffering,
            -> BufferingIndicator(compact = true)
            PlaybackStatus.Playing,
            PlaybackStatus.Paused,
            PlaybackStatus.Completed,
            ->
                if (controlsVisible) {
                    KidsControlButton(
                        icon =
                            if (replay) {
                                Icons.Filled.Replay
                            } else if (playing) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                        description =
                            stringResource(
                                if (replay) {
                                    Res.string.kids_replay
                                } else if (playing) {
                                    Res.string.player_pause_cd
                                } else {
                                    Res.string.player_play_cd
                                },
                            ),
                        onClick =
                            if (replay) {
                                onReplay
                            } else if (playing) {
                                onPause
                            } else {
                                onPlay
                            },
                        primary = true,
                    )
                }
            PlaybackStatus.Failed -> Unit
        }
    }
}

@Composable
private fun KidsPlaybackOptions(
    content: PlayerUiState.Content,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    onKeepControlsAlive: () -> Unit,
) {
    val audioActionAvailable = content.audioOptions.size > 1 || content.timingState.audio.isSupported
    val subtitleActionAvailable = content.hasSubtitlePickerChoice()
    val qualityActionAvailable = content.qualityOptions.isNotEmpty()
    if (audioActionAvailable || subtitleActionAvailable || qualityActionAvailable) {
        Box {
            KidsControlButton(
                icon = Icons.Filled.MoreVert,
                description = stringResource(Res.string.kids_more_playback_options),
                onClick = {
                    onExpandedChange(true)
                    onKeepControlsAlive()
                },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                if (audioActionAvailable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.player_audio)) },
                        onClick = {
                            onExpandedChange(false)
                            onKeepControlsAlive()
                            onShowPicker(PlayerPicker.Audio)
                        },
                    )
                }
                if (subtitleActionAvailable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.player_subtitles)) },
                        onClick = {
                            onExpandedChange(false)
                            onKeepControlsAlive()
                            onShowPicker(PlayerPicker.Subtitles)
                        },
                    )
                }
                if (qualityActionAvailable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.player_quality)) },
                        onClick = {
                            onExpandedChange(false)
                            onKeepControlsAlive()
                            onShowPicker(PlayerPicker.Quality)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KidsControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(if (primary) Dimensions.playerPrimaryControlSize else Dimensions.minTouchTarget)
                .background(if (primary) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (primary) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier.size(if (primary) Dimensions.playerPrimaryIconSize else Dimensions.playerControlIconSize),
        )
    }
}

@Composable
private fun KidsRecommendations(
    playingTitle: String,
    landscape: Boolean,
    state: KidsWatchUiState,
    session: Session,
    onSelectAsset: (KidsWatchItem) -> Unit,
    onRetryCatalogue: () -> Unit,
    onRetryOffline: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val density = LocalDensity.current
    val leftInset = with(density) { WindowInsets.safeDrawing.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toDp() }
    val rightInset = with(density) { WindowInsets.safeDrawing.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toDp() }
    val edgeSpacing = Dimensions.contentSpacing
    val leftPadding = edgeSpacing + if (landscape) 0.dp else leftInset
    val rightPadding = edgeSpacing + rightInset
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Dimensions.libraryCardWidth.tileScaled()),
        state = rememberLazyGridState(),
        modifier = modifier,
        contentPadding =
            PaddingValues.Absolute(
                left = leftPadding,
                right = rightPadding,
                top = edgeSpacing,
                bottom = safePadding.calculateBottomPadding() + edgeSpacing,
            ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        if (playingTitle.isNotBlank()) {
            section(key = "playing-title") {
                Text(playingTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when (state.catalogueState) {
            KidsCatalogueState.Loading ->
                section(key = "catalogue-loading") {
                    CircularProgressIndicator()
                }
            KidsCatalogueState.Empty ->
                section(key = "catalogue-empty") {
                    Text(stringResource(Res.string.kids_empty))
                }
            KidsCatalogueState.Error ->
                section(key = "catalogue-error") {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                        Text(stringResource(Res.string.kids_retry_catalogue))
                        Button(onClick = onRetryCatalogue) { Text(stringResource(Res.string.detail_retry)) }
                    }
                }
            KidsCatalogueState.Content -> Unit
        }
        if (state.recommendations.isNotEmpty()) {
            section(key = "recommendations-title") {
                Text(stringResource(Res.string.kids_recommendations), style = MaterialTheme.typography.titleMedium)
            }
            recommendationCards(
                keyPrefix = "recommendation",
                entries = state.recommendations,
                session = session,
                onSelectAsset = onSelectAsset,
            )
        } else if (state.catalogueState == KidsCatalogueState.Error && state.offlineFallback.isNotEmpty()) {
            section(key = "offline-title") {
                Text(stringResource(Res.string.kids_recommendations), style = MaterialTheme.typography.titleMedium)
            }
            recommendationCards(
                keyPrefix = "offline",
                entries = state.offlineFallback,
                session = session,
                onSelectAsset = onSelectAsset,
            )
        }
        if (state.offlineState == KidsOfflineState.Error && session.enableContentDownloading) {
            section(key = "offline-error") {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                    Text(stringResource(Res.string.kids_downloads_error))
                    Button(onClick = onRetryOffline) { Text(stringResource(Res.string.detail_retry)) }
                }
            }
        }
        if (state.hasMissingOfflineArtifacts && session.enableContentDownloading) {
            section(key = "offline-recheck") {
                Button(onClick = onRetryOffline) { Text(stringResource(Res.string.kids_retry_offline)) }
            }
        }
        if (session.enableContentDownloading) {
            section(key = "downloads") {
                Button(onClick = onOpenDownloads, modifier = Modifier.heightIn(min = Dimensions.minTouchTarget)) {
                    Text(stringResource(Res.string.kids_downloads))
                }
            }
        }
    }
}

private fun LazyGridScope.section(
    key: String,
    content: @Composable () -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Box { content() }
    }
}

private fun LazyGridScope.recommendationCards(
    keyPrefix: String,
    entries: List<KidsWatchItem>,
    session: Session,
    onSelectAsset: (KidsWatchItem) -> Unit,
) {
    items(
        items = entries,
        key = { item -> "$keyPrefix-${item.card.id}-${item.offlineDownloadId?.value.orEmpty()}" },
        contentType = { "recommendation" },
    ) { item ->
        MediaCard(
            item = item.card,
            session = session,
            onClick = { onSelectAsset(item) },
            useWideCard = true,
            fillWidth = true,
            imageAspectRatio = Dimensions.videoCardAspectRatio,
            titleMaxLines = 1,
            showSubtitle = false,
            durationLabel = item.card.runtimeText,
        )
    }
}

private data class KidsPlayerLayout(
    val landscape: Boolean,
    val fullscreen: Boolean,
)

private const val KIDS_FULLSCREEN_DRAG_THRESHOLD_FRACTION = 0.2f
private const val KIDS_FULLSCREEN_DRAG_MAX_FRACTION = 0.5f

private class KidsFullscreenDragState(
    private val scope: CoroutineScope,
) {
    private var dragging by mutableStateOf(false)
    private var distancePx by mutableFloatStateOf(0f)
    private var startOffsetPx = 0f
    private var thresholdPx = 0f
    private val settlingOffset = Animatable(0f)
    private var settlingJob: Job? = null
    private var disposed = false

    val offsetPx: Float get() = if (dragging) distancePx else settlingOffset.value

    fun dragTo(
        distance: Float,
        maximumPx: Float,
        direction: PlayerFullscreenDragDirection,
        thresholdPx: Float,
    ) {
        if (disposed) return
        if (!dragging) {
            this.thresholdPx = thresholdPx
            startOffsetPx = settlingOffset.value
            settlingJob?.cancel()
            dragging = true
        }
        val minimum = if (direction == PlayerFullscreenDragDirection.Up) -maximumPx else minOf(0f, startOffsetPx)
        val maximum = if (direction == PlayerFullscreenDragDirection.Down) maximumPx else maxOf(0f, startOffsetPx)
        distancePx = (startOffsetPx + distance).coerceIn(minimum, maximum)
    }

    fun finish(
        cancelled: Boolean,
        direction: PlayerFullscreenDragDirection,
        onCommit: () -> Unit,
    ) {
        if (disposed || !dragging) return
        val releasedOffset = distancePx
        val shouldCommit = !cancelled && releasedOffset * direction.sign >= thresholdPx
        settlingJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                settlingOffset.snapTo(releasedOffset)
                dragging = false
                if (shouldCommit) onCommit()
                settlingOffset.animateTo(0f, tween(PLAYER_LAYOUT_ANIMATION_MS))
            }
    }

    fun cancel() {
        disposed = true
        settlingJob?.cancel()
    }
}
