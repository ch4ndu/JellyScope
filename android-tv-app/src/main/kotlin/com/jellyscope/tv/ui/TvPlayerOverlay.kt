// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.player.HoldSeekDirection
import com.jellyscope.ui.screen.player.PlayerPicker
import com.jellyscope.ui.screen.player.PlayerResizeMode
import com.jellyscope.ui.screen.player.PlayerUiState
import com.jellyscope.ui.screen.player.hasSubtitlePickerChoice
import com.jellyscope.ui.screen.player.speedLabel
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.flow.StateFlow
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvPlayerOverlay(
    session: Session,
    content: PlayerUiState.Content,
    currentItemId: String,
    playbackStateFlow: StateFlow<PlaybackState>,
    initialFocusPicker: PlayerPicker?,
    initialFocusMenu: TvPlayerLocalMenu?,
    initialFocusSeek: Boolean,
    requestInitialFocus: Boolean,
    queueOpen: Boolean,
    onQueueOpenChanged: (Boolean) -> Unit,
    onInitialFocusConsumed: () -> Unit,
    onTogglePlayPause: () -> Unit,
    playFocusRequester: FocusRequester,
    pendingSeekTargetMs: () -> Long?,
    onSeekKeyDown: (HoldSeekDirection) -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    onShowLocalMenu: (TvPlayerLocalMenu) -> Unit,
    onToggleDebugOverlay: () -> Unit,
    onSelectResizeMode: (PlayerResizeMode) -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seekRequester = remember { FocusRequester() }
    val subtitlesRequester = remember { FocusRequester() }
    val audioRequester = remember { FocusRequester() }
    val qualityRequester = remember { FocusRequester() }
    val chaptersRequester = remember { FocusRequester() }
    val speedRequester = remember { FocusRequester() }
    val styleRequester = remember { FocusRequester() }
    val resizeRequester = remember { FocusRequester() }
    val debugRequester = remember { FocusRequester() }
    val queueRequester = remember { FocusRequester() }
    val playlist = content.playlist
    val playbackStatus = content.playbackState.status
    // Timing support alone does not mean the item has subtitles.
    val showSubtitleButton = content.hasSubtitlePickerChoice()
    val showAudioButton = content.audioOptions.size > 1 || content.timingState.audio.isSupported
    val showSubtitleStyleButton = content.subtitleStyleable
    val showChaptersButton = content.chapters.isNotEmpty()
    var seekFocused by remember { mutableStateOf(false) }

    // Initial focus depends on whether controls or scrub invoked the overlay.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (requestInitialFocus) {
            val requester =
                when {
                    initialFocusPicker == PlayerPicker.Subtitles && showSubtitleButton -> subtitlesRequester
                    initialFocusPicker == PlayerPicker.Audio && showAudioButton -> audioRequester
                    initialFocusPicker == PlayerPicker.Quality -> qualityRequester
                    initialFocusMenu == TvPlayerLocalMenu.Chapters && showChaptersButton -> chaptersRequester
                    initialFocusMenu == TvPlayerLocalMenu.Speed -> speedRequester
                    initialFocusMenu == TvPlayerLocalMenu.SubtitleStyle && showSubtitleStyleButton -> styleRequester
                    initialFocusMenu == TvPlayerLocalMenu.Resize -> resizeRequester
                    initialFocusSeek -> seekRequester
                    else -> playFocusRequester
                }
            requester.requestFocusSafely()
        }
        onInitialFocusConsumed()
    }

    // Hand off focus after the queue or controls page enters composition.
    var queueWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(queueOpen) {
        if (queueOpen) {
            queueWasOpen = true
            withFrameNanos { }
            queueRequester.requestFocusSafely()
        } else if (queueWasOpen) {
            queueWasOpen = false
            withFrameNanos { }
            playFocusRequester.requestFocusSafely()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap),
    ) {
        // The scrub section alone collects per-tick playback state.
        TvPlayerScrubSection(
            session = session,
            currentItemId = currentItemId,
            trickplay = content.trickplay,
            chapters = content.chapters,
            playbackStateFlow = playbackStateFlow,
            seekRequester = seekRequester,
            playRequester = playFocusRequester,
            seekFocused = seekFocused,
            onSeekFocusChanged = { focused -> seekFocused = focused },
            pendingSeekTargetMs = pendingSeekTargetMs,
            onSeekKeyDown = onSeekKeyDown,
        )
        // Key interception swaps the exclusive controls and queue pages.
        AnimatedContent(
            targetState = queueOpen && playlist != null,
            transitionSpec = {
                (
                    if (targetState) {
                        slideInVertically(tween(QUEUE_SLIDE_MS)) { height -> height } +
                            fadeIn(tween(QUEUE_SLIDE_MS)) togetherWith
                            slideOutVertically(tween(QUEUE_SLIDE_MS)) { height -> -height } +
                            fadeOut(tween(QUEUE_SLIDE_MS))
                    } else {
                        slideInVertically(tween(QUEUE_SLIDE_MS)) { height -> -height } +
                            fadeIn(tween(QUEUE_SLIDE_MS)) togetherWith
                            slideOutVertically(tween(QUEUE_SLIDE_MS)) { height -> height } +
                            fadeOut(tween(QUEUE_SLIDE_MS))
                    }
                ).using(SizeTransform(clip = true))
            },
            label = "player-bottom-pager",
        ) { showQueue ->
            if (showQueue && playlist != null) {
                TvQueueRibbon(
                    session = session,
                    playlist = playlist,
                    ribbonRequester = queueRequester,
                    onPlayQueueItem = onPlayQueueItem,
                    modifier =
                        Modifier.onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionUp &&
                                run {
                                    onQueueOpenChanged(false)
                                    true
                                }
                        },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap)) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .onPreviewKeyEvent { event ->
                                    event.type == KeyEventType.KeyDown &&
                                        event.key == Key.DirectionDown &&
                                        playlist != null &&
                                        run {
                                            onQueueOpenChanged(true)
                                            true
                                        }
                                },
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.Center),
                        ) {
                            val onSubtitlesClick = remember(onShowPicker) { { onShowPicker(PlayerPicker.Subtitles) } }
                            val onAudioClick = remember(onShowPicker) { { onShowPicker(PlayerPicker.Audio) } }
                            val onQualityClick = remember(onShowPicker) { { onShowPicker(PlayerPicker.Quality) } }
                            val onChaptersClick = remember(onShowLocalMenu) { { onShowLocalMenu(TvPlayerLocalMenu.Chapters) } }
                            val onSpeedClick = remember(onShowLocalMenu) { { onShowLocalMenu(TvPlayerLocalMenu.Speed) } }
                            val onSubtitleStyleClick = remember(onShowLocalMenu) { { onShowLocalMenu(TvPlayerLocalMenu.SubtitleStyle) } }
                            val onResizeClick = remember(onShowLocalMenu) { { onShowLocalMenu(TvPlayerLocalMenu.Resize) } }

                            Row(
                                modifier = Modifier.align(Alignment.CenterStart),
                                horizontalArrangement =
                                    Arrangement.spacedBy(TvDimens.playerTransportControlGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (showSubtitleButton) {
                                    TvCircleButton(
                                        contentDescription = stringResource(R.string.tv_subtitles),
                                        focusRequester = subtitlesRequester,
                                        upRequester = seekRequester,
                                        onClick = onSubtitlesClick,
                                        icon = PlayerButtonIcon.Subtitles,
                                        size = TvDimens.playerOptionButtonSize,
                                    )
                                }
                                if (showAudioButton) {
                                    TvCircleButton(
                                        contentDescription = stringResource(R.string.tv_audio),
                                        focusRequester = audioRequester,
                                        upRequester = seekRequester,
                                        onClick = onAudioClick,
                                        icon = PlayerButtonIcon.Audio,
                                        size = TvDimens.playerOptionButtonSize,
                                    )
                                }
                                TvCircleButton(
                                    contentDescription = stringResource(R.string.tv_quality),
                                    focusRequester = qualityRequester,
                                    upRequester = seekRequester,
                                    onClick = onQualityClick,
                                    icon = PlayerButtonIcon.Quality,
                                    size = TvDimens.playerOptionButtonSize,
                                )
                                if (showChaptersButton) {
                                    TvCircleButton(
                                        contentDescription = stringResource(R.string.tv_chapters),
                                        focusRequester = chaptersRequester,
                                        upRequester = seekRequester,
                                        onClick = onChaptersClick,
                                        icon = PlayerButtonIcon.Chapters,
                                        size = TvDimens.playerOptionButtonSize,
                                    )
                                }
                            }
                            TvCircleButton(
                                contentDescription =
                                    if (playbackStatus == PlaybackStatus.Playing) {
                                        stringResource(R.string.tv_player_pause_cd)
                                    } else {
                                        stringResource(R.string.tv_player_play_cd)
                                    },
                                focusRequester = playFocusRequester,
                                upRequester = seekRequester,
                                modifier = Modifier.align(Alignment.Center),
                                size = TvDimens.playerPlayButtonSize,
                                onClick = onTogglePlayPause,
                                icon =
                                    if (playbackStatus == PlaybackStatus.Playing) {
                                        PlayerButtonIcon.Pause
                                    } else {
                                        PlayerButtonIcon.Play
                                    },
                            )
                            Row(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                horizontalArrangement =
                                    Arrangement.spacedBy(TvDimens.playerTransportControlGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TvCircleButton(
                                    contentDescription =
                                        stringResource(
                                            R.string.tv_speed_cd,
                                            content.playbackSpeed.speedLabel(),
                                        ),
                                    focusRequester = speedRequester,
                                    upRequester = seekRequester,
                                    onClick = onSpeedClick,
                                    icon = PlayerButtonIcon.Speed,
                                    size = TvDimens.playerOptionButtonSize,
                                )
                                if (showSubtitleStyleButton) {
                                    TvCircleButton(
                                        contentDescription = stringResource(R.string.tv_subtitle_style),
                                        focusRequester = styleRequester,
                                        upRequester = seekRequester,
                                        onClick = onSubtitleStyleClick,
                                        icon = PlayerButtonIcon.SubtitleStyle,
                                        size = TvDimens.playerOptionButtonSize,
                                    )
                                }
                                TvCircleButton(
                                    contentDescription = stringResource(R.string.tv_resize_mode_cd, content.resizeMode.label()),
                                    focusRequester = resizeRequester,
                                    upRequester = seekRequester,
                                    onClick = onResizeClick,
                                    icon = PlayerButtonIcon.Resize,
                                    size = TvDimens.playerOptionButtonSize,
                                )
                                TvCircleButton(
                                    contentDescription = stringResource(R.string.tv_playback_info),
                                    focusRequester = debugRequester,
                                    upRequester = seekRequester,
                                    onClick = onToggleDebugOverlay,
                                    icon = PlayerButtonIcon.Debug,
                                    size = TvDimens.playerOptionButtonSize,
                                )
                            }
                        }
                    }
                    // Static hint for the hidden queue page.
                    if (playlist != null) {
                        TvText(
                            text = stringResource(R.string.tv_up_next),
                            style = TvPlayerSmallTitleStyle,
                            color = LocalJellyfinPalette.current.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// Isolates per-tick recomposition to scrub controls.
