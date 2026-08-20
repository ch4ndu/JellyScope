// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.MAX_VOLUME_PERCENT
import com.jellyscope.core.domain.playback.MIN_VOLUME_PERCENT
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import com.jellyscope.ui.generated.resources.player_audio
import com.jellyscope.ui.generated.resources.player_chapters
import com.jellyscope.ui.generated.resources.player_debug_overlay
import com.jellyscope.ui.generated.resources.player_fullscreen_enter_cd
import com.jellyscope.ui.generated.resources.player_fullscreen_exit_cd
import com.jellyscope.ui.generated.resources.player_pause_cd
import com.jellyscope.ui.generated.resources.player_play_cd
import com.jellyscope.ui.generated.resources.player_quality
import com.jellyscope.ui.generated.resources.player_queue
import com.jellyscope.ui.generated.resources.player_speed
import com.jellyscope.ui.generated.resources.player_subtitle_style
import com.jellyscope.ui.generated.resources.player_subtitles
import com.jellyscope.ui.generated.resources.player_volume
import com.jellyscope.ui.generated.resources.player_volume_mute
import com.jellyscope.ui.generated.resources.player_volume_unmute
import com.jellyscope.ui.platform.LocalFullscreenToggle
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun PlayerTopBar(
    content: PlayerUiState.Content,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(Dimensions.minTouchTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.detail_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playerTitle(content.metadata),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            playerSubtitle(content.metadata)?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun PlayerControls(
    content: PlayerUiState.Content,
    playbackStateFlow: StateFlow<PlaybackState>,
    session: Session,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    onKeepControlsAlive: () -> Unit,
    onCycleResizeMode: () -> Unit,
    debugOverlayVisible: Boolean,
    onToggleDebugOverlay: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardSeekTargetMs: Long? = null,
    onPointerScrubActiveChange: (Boolean) -> Unit = {},
    onControlsScrollActiveChange: (Boolean) -> Unit = {},
) {
    // Only leaves that render live values collect per-tick state.
    val controlsScrollState = rememberScrollState()
    val currentOnControlsScrollActiveChange by rememberUpdatedState(onControlsScrollActiveChange)
    val currentOnKeepControlsAlive by rememberUpdatedState(onKeepControlsAlive)

    // Suppress auto-hide during scroll and re-arm it once at the end.
    LaunchedEffect(controlsScrollState) {
        var wasScrolling = false
        snapshotFlow { controlsScrollState.isScrollInProgress }
            .collect { scrolling ->
                currentOnControlsScrollActiveChange(scrolling)
                if (wasScrolling && !scrolling) {
                    currentOnKeepControlsAlive()
                }
                wasScrolling = scrolling
            }
    }
    DisposableEffect(Unit) {
        // Do not leave auto-hide suppressed after disposal.
        onDispose { currentOnControlsScrollActiveChange(false) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        PlayerSeekSection(
            content = content,
            playbackStateFlow = playbackStateFlow,
            session = session,
            keyboardSeekTargetMs = keyboardSeekTargetMs,
            onSeekTo = onSeekTo,
            onKeepControlsAlive = onKeepControlsAlive,
            onPointerScrubActiveChange = onPointerScrubActiveChange,
        )
        PlayerControlStripHost(
            content = content,
            playbackStateFlow = playbackStateFlow,
            controlsScrollState = controlsScrollState,
            onPlay = onPlay,
            onPause = onPause,
            onSetVolume = onSetVolume,
            onToggleMute = onToggleMute,
            onShowPicker = onShowPicker,
            onKeepControlsAlive = onKeepControlsAlive,
            onCycleResizeMode = onCycleResizeMode,
            debugOverlayVisible = debugOverlayVisible,
            onToggleDebugOverlay = onToggleDebugOverlay,
        )
    }
}

/** Controls that recompose with live playback position. */
@Composable
private fun ColumnScope.PlayerSeekSection(
    content: PlayerUiState.Content,
    playbackStateFlow: StateFlow<PlaybackState>,
    session: Session,
    keyboardSeekTargetMs: Long?,
    onSeekTo: (Long) -> Unit,
    onKeepControlsAlive: () -> Unit,
    onPointerScrubActiveChange: (Boolean) -> Unit,
) {
    val playbackState by playbackStateFlow.collectAsStateWithLifecycle()
    val durationMs = playbackState.durationMs?.takeIf { duration -> duration > 0L }
    var seekPositionMs by remember { mutableLongStateOf(playbackState.positionMs) }
    var scrubPreviewPositionMs by remember { mutableStateOf<Long?>(null) }

    // Pointer scrubbing overrides the keyboard target until playback catches up.
    LaunchedEffect(playbackState.positionMs, keyboardSeekTargetMs) {
        if (scrubPreviewPositionMs == null) {
            seekPositionMs = keyboardSeekTargetMs ?: playbackState.positionMs
        }
    }

    TrickplayPreview(
        trickplay = content.trickplay,
        tileUrls = content.trickplayTileUrls,
        positionMs = scrubPreviewPositionMs ?: keyboardSeekTargetMs,
        session = session,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    SeekSlider(
        positionMs = seekPositionMs,
        durationMs = durationMs,
        bufferedPositionMs = playbackState.bufferedPositionMs,
        chapters = content.chapters,
        onPositionChange = { positionMs ->
            onKeepControlsAlive()
            seekPositionMs = positionMs
        },
        onSeekTo = { positionMs ->
            onKeepControlsAlive()
            onSeekTo(positionMs)
        },
        onScrubPreviewPositionChange = { positionMs -> scrubPreviewPositionMs = positionMs },
        onScrubFinished = { scrubPreviewPositionMs = null },
        onScrubActiveChange = onPointerScrubActiveChange,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${formatDuration(seekPositionMs)} / ${formatDuration(durationMs)}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Collects only the playing state needed by the button strip. */
@Composable
private fun PlayerControlStripHost(
    content: PlayerUiState.Content,
    playbackStateFlow: StateFlow<PlaybackState>,
    controlsScrollState: ScrollState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    onKeepControlsAlive: () -> Unit,
    onCycleResizeMode: () -> Unit,
    debugOverlayVisible: Boolean,
    onToggleDebugOverlay: () -> Unit,
) {
    val playingFlow =
        remember(playbackStateFlow) {
            playbackStateFlow
                .map { state -> state.status == PlaybackStatus.Playing }
                .distinctUntilChanged()
        }
    val playing by
        playingFlow.collectAsStateWithLifecycle(
            initialValue = playbackStateFlow.value.status == PlaybackStatus.Playing,
        )
    PlayerControlStrip(
        content = content,
        playing = playing,
        controlsScrollState = controlsScrollState,
        onPlay = onPlay,
        onPause = onPause,
        onSetVolume = onSetVolume,
        onToggleMute = onToggleMute,
        onShowPicker = onShowPicker,
        onKeepControlsAlive = onKeepControlsAlive,
        onCycleResizeMode = onCycleResizeMode,
        debugOverlayVisible = debugOverlayVisible,
        onToggleDebugOverlay = onToggleDebugOverlay,
    )
}

@Composable
private fun PlayerControlStrip(
    content: PlayerUiState.Content,
    playing: Boolean,
    controlsScrollState: ScrollState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    onKeepControlsAlive: () -> Unit,
    onCycleResizeMode: () -> Unit,
    debugOverlayVisible: Boolean,
    onToggleDebugOverlay: () -> Unit,
) {
    val playPauseContentDescription =
        if (playing) {
            stringResource(Res.string.player_pause_cd)
        } else {
            stringResource(Res.string.player_play_cd)
        }
    val subtitlesContentDescription = stringResource(Res.string.player_subtitles)
    val audioContentDescription = stringResource(Res.string.player_audio)
    val qualityContentDescription = stringResource(Res.string.player_quality)
    val chaptersContentDescription = stringResource(Res.string.player_chapters)
    val speedContentDescription = stringResource(Res.string.player_speed)
    val subtitleStyleContentDescription = stringResource(Res.string.player_subtitle_style)
    val platformCapabilities = LocalPlatformCapabilities.current
    val fullscreenToggle = LocalFullscreenToggle.current
    val showFullscreenControl = platformCapabilities.playerFullscreenControl
    val useDesktopControlLayout =
        platformCapabilities.desktopPlayerControls &&
            LocalWindowWidthTier.current.isDesktopPlayerWide()
    // Timing support alone does not mean the item has subtitles.
    val showSubtitleControls = content.hasSubtitlePickerChoice()
    val showSubtitleStyleControl = content.subtitleStyleable
    val showChapterControl = content.chapters.isNotEmpty()
    val resizeContentDescription =
        resizeModeLabel(
            resizeMode = content.resizeMode,
            desktopResizeModes = platformCapabilities.desktopResizeModes,
        )
    val queueContentDescription = stringResource(Res.string.player_queue)
    val debugOverlayContentDescription = stringResource(Res.string.player_debug_overlay)
    val fullscreenContentDescription =
        if (fullscreenToggle.isFullscreen) {
            stringResource(Res.string.player_fullscreen_exit_cd)
        } else {
            stringResource(Res.string.player_fullscreen_enter_cd)
        }
    val showPicker = { picker: PlayerPicker ->
        onKeepControlsAlive()
        onShowPicker(picker)
    }
    val volumeControl = content.volumeControl
    val volumeContentDescription = stringResource(Res.string.player_volume)
    val muteContentDescription =
        stringResource(
            if (volumeControl?.muted == true) Res.string.player_volume_unmute else Res.string.player_volume_mute,
        )

    val playPauseButton: @Composable () -> Unit = {
        PlayerControlIconButton(
            icon =
                if (playing) {
                    Icons.Filled.Pause
                } else {
                    Icons.Filled.PlayArrow
                },
            contentDescription = playPauseContentDescription,
            onClick = {
                onKeepControlsAlive()
                if (playing) {
                    onPause()
                } else {
                    onPlay()
                }
            },
            prominent = true,
        )
    }

    val leftButtons: @Composable () -> Unit = {
        if (showSubtitleControls) {
            PlayerControlIconButton(
                icon = Icons.Filled.ClosedCaption,
                contentDescription = subtitlesContentDescription,
                onClick = { showPicker(PlayerPicker.Subtitles) },
            )
        }
        if (content.audioUnavailable) {
            AudioUnavailableGlyph()
        }
        if (content.audioOptions.size > 1 || content.timingState.audio.isSupported) {
            PlayerControlIconButton(
                icon = Icons.Filled.Audiotrack,
                contentDescription = audioContentDescription,
                onClick = { showPicker(PlayerPicker.Audio) },
            )
        }
        if (content.qualityOptions.isNotEmpty()) {
            PlayerControlIconButton(
                icon = Icons.Filled.HighQuality,
                contentDescription = qualityContentDescription,
                onClick = { showPicker(PlayerPicker.Quality) },
            )
        }
        if (showChapterControl) {
            PlayerControlIconButton(
                icon = Icons.AutoMirrored.Filled.List,
                contentDescription = chaptersContentDescription,
                onClick = { showPicker(PlayerPicker.Chapters) },
            )
        }
    }

    val rightButtons: @Composable () -> Unit = {
        volumeControl?.let { volume ->
            var sliderValue by
                remember {
                    mutableStateOf(
                        if (volume.muted) MIN_VOLUME_PERCENT.toFloat() else volume.volumePercent.toFloat(),
                    )
                }
            var sliderMuted by remember(volume.muted) { mutableStateOf(volume.muted) }
            val volumeInteractionSource = remember { MutableInteractionSource() }
            val volumeDragging by volumeInteractionSource.collectIsDraggedAsState()
            // Ignore lagging published values during and immediately after a drag.
            LaunchedEffect(volume) {
                if (!volumeDragging) {
                    sliderValue = if (volume.muted) MIN_VOLUME_PERCENT.toFloat() else volume.volumePercent.toFloat()
                }
            }
            val volumeDragScope = rememberCoroutineScope()
            val pendingVolumePublish = remember { PendingVolumePublish() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            ) {
                PlayerControlIconButton(
                    icon = if (volume.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = muteContentDescription,
                    onClick = {
                        onKeepControlsAlive()
                        onToggleMute()
                    },
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { value ->
                        sliderValue = value
                        if (sliderMuted) {
                            sliderMuted = false
                            onToggleMute()
                        }
                        onKeepControlsAlive()
                        // Coalesce only continuous drag updates; key repeats publish directly.
                        pendingVolumePublish.schedule(volumeDragScope) {
                            onSetVolume(sliderValue.roundToInt())
                        }
                    },
                    onValueChangeFinished = {
                        // Always publish the settled value.
                        pendingVolumePublish.cancel()
                        onSetVolume(sliderValue.roundToInt())
                    },
                    valueRange = MIN_VOLUME_PERCENT.toFloat()..MAX_VOLUME_PERCENT.toFloat(),
                    interactionSource = volumeInteractionSource,
                    modifier =
                        Modifier
                            .width(Dimensions.playerVolumeSliderWidth)
                            .semantics { contentDescription = volumeContentDescription },
                    thumb = {
                        ThinSliderThumb(
                            interactionSource = volumeInteractionSource,
                            restingSize = Dimensions.playerSliderThumbSize,
                            activeSize = Dimensions.playerSliderThumbActiveSize,
                        )
                    },
                    track = { sliderState ->
                        ThinSliderTrack(
                            progressFraction = sliderState.value / MAX_VOLUME_PERCENT.toFloat(),
                            bufferedFraction = null,
                        )
                    },
                )
            }
        }
        PlayerControlIconButton(
            icon = Icons.Filled.Speed,
            contentDescription = speedContentDescription,
            onClick = { showPicker(PlayerPicker.Speed) },
        )
        if (showSubtitleStyleControl) {
            PlayerControlIconButton(
                icon = Icons.Filled.Subtitles,
                contentDescription = subtitleStyleContentDescription,
                onClick = { showPicker(PlayerPicker.SubtitleStyle) },
            )
        }
        PlayerControlIconButton(
            icon = Icons.Filled.AspectRatio,
            contentDescription = resizeContentDescription,
            onClick = {
                if (platformCapabilities.desktopResizeModes) {
                    showPicker(PlayerPicker.Resize)
                } else {
                    onKeepControlsAlive()
                    onCycleResizeMode()
                }
            },
        )
        if (showFullscreenControl) {
            PlayerControlIconButton(
                icon =
                    if (fullscreenToggle.isFullscreen) {
                        Icons.Filled.FullscreenExit
                    } else {
                        Icons.Filled.Fullscreen
                    },
                contentDescription = fullscreenContentDescription,
                onClick = {
                    onKeepControlsAlive()
                    fullscreenToggle.toggleFullscreen()
                },
            )
        }
        // Movies have no queue action.
        if (content.playlist != null) {
            PlayerControlIconButton(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = queueContentDescription,
                onClick = { showPicker(PlayerPicker.Queue) },
            )
        }
        PlayerControlIconButton(
            icon = Icons.Filled.BugReport,
            contentDescription = debugOverlayContentDescription,
            onClick = onToggleDebugOverlay,
            active = debugOverlayVisible,
        )
    }

    if (useDesktopControlLayout) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leftButtons()
            }
            Box(modifier = Modifier.align(Alignment.Center)) {
                playPauseButton()
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rightButtons()
            }
        }
    } else {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(controlsScrollState),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            playPauseButton()
            leftButtons()
            rightButtons()
        }
    }
}

/** Publishes the latest drag value at a bounded cadence. */
private class PendingVolumePublish {
    private var job: Job? = null

    fun schedule(
        scope: CoroutineScope,
        publish: () -> Unit,
    ) {
        if (job?.isActive == true) {
            return
        }
        job =
            scope.launch {
                delay(VOLUME_DRAG_PUBLISH_INTERVAL_MS)
                publish()
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

// Three frames at 60 Hz.
private const val VOLUME_DRAG_PUBLISH_INTERVAL_MS = 50L

@Composable
private fun PlayerControlIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    prominent: Boolean = false,
    active: Boolean = false,
) {
    val size =
        if (prominent) {
            Dimensions.playerPrimaryControlSize
        } else {
            Dimensions.minTouchTarget
        }
    val containerColor =
        if (prominent || active) {
            MaterialTheme.colorScheme.primary.copy(alpha = PLAYER_PRIMARY_CONTROL_BACKGROUND_ALPHA)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = PLAYER_CONTROL_BACKGROUND_ALPHA)
        }
    val iconColor =
        if (prominent || active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(size)
                .clip(MaterialTheme.shapes.large)
                .background(containerColor),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(Dimensions.playerControlIconSize),
        )
    }
}
