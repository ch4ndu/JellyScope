// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNotice
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthGuidanceReason
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.player.PlayerBackendNotice
import com.jellyscope.ui.screen.player.PlayerDebugInfo
import com.jellyscope.ui.screen.player.PlayerDebugRowModel
import com.jellyscope.ui.screen.player.PlayerDebugSection
import com.jellyscope.ui.screen.player.PlayerPlaybackChangeNotice
import com.jellyscope.ui.screen.player.PlayerPlaybackChangeOperation
import com.jellyscope.ui.screen.player.PlayerSoftwarePlaybackRecovery
import com.jellyscope.ui.screen.player.playerDebugMpvLabels
import com.jellyscope.ui.screen.player.playerDebugSections
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.flow.StateFlow
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvDebugInfoOverlay(
    debugInfo: PlayerDebugInfo?,
    playbackStateFlow: StateFlow<PlaybackState>,
    runtimeDiagnosticsFlow: StateFlow<PlaybackRuntimeDiagnostics>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .heightIn(max = TvDimens.playerDebugOverlayMaxHeight)
                .clip(
                    RoundedCornerShape(
                        bottomStart = TvDimens.panelRadius,
                        bottomEnd = TvDimens.panelRadius,
                    ),
                ).background(LocalJellyfinPalette.current.gradientBottom.copy(alpha = TV_DEBUG_OVERLAY_ALPHA))
                .padding(
                    start = TvDimens.playerPickerPadding,
                    end = TvDimens.playerPickerPadding,
                    top = TvDimens.playerPickerPadding,
                    bottom = TvDimens.playerPickerPadding / 2,
                ),
        verticalArrangement = Arrangement.spacedBy(TvDimens.playerDebugRowGap),
    ) {
        TvText(
            text = stringResource(R.string.tv_playback_info),
            style = TvPlayerSectionTitleStyle,
            maxLines = 1,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = TvDimens.playerPickerDividerVerticalPadding)
                    .height(TvDimens.playerPickerDividerHeight)
                    .background(Color.White.copy(alpha = 0.12f)),
        )
        TvDebugMenuContent(
            debugInfo = debugInfo,
            playbackStateFlow = playbackStateFlow,
            runtimeDiagnosticsFlow = runtimeDiagnosticsFlow,
        )
    }
}

@Composable
private fun TvDebugMenuContent(
    debugInfo: PlayerDebugInfo?,
    playbackStateFlow: StateFlow<PlaybackState>,
    runtimeDiagnosticsFlow: StateFlow<PlaybackRuntimeDiagnostics>,
) {
    val playback by playbackStateFlow.collectAsStateWithLifecycle()
    val runtimeDiagnostics by runtimeDiagnosticsFlow.collectAsStateWithLifecycle()
    val mpvLabels = playerDebugMpvLabels()
    val genericRows =
        remember(debugInfo, playback, runtimeDiagnostics, mpvLabels) {
            playerDebugSections(
                debugInfo = debugInfo,
                playbackState = playback,
                runtimeDiagnostics = runtimeDiagnostics,
                mpvLabels = mpvLabels,
            ).flatMap(PlayerDebugSection::rows)
        }
    val splitIndex = (genericRows.size + 1) / 2
    Column(
        verticalArrangement = Arrangement.spacedBy(TvDimens.playerDebugRowGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TvDimens.playerDebugRowGap),
            ) {
                genericRows.take(splitIndex).forEach { row -> TvDebugRow(row) }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TvDimens.playerDebugRowGap),
            ) {
                genericRows.drop(splitIndex).forEach { row -> TvDebugRow(row) }
            }
        }
    }
}

@Composable
private fun TvDebugRow(row: PlayerDebugRowModel) {
    TvDebugRow(
        label = row.label,
        value = row.value,
        emphasize = row.emphasize,
    )
}

@Composable
private fun TvDebugRow(
    label: String,
    value: String,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.playerBadgeHorizontalPadding),
    ) {
        TvText(
            text = label,
            modifier = Modifier.weight(0.4f),
            style = TvSecondaryStyle,
        )
        TvText(
            text = value,
            modifier = Modifier.weight(0.6f),
            style = TvSecondaryStyle.copy(fontWeight = FontWeight.SemiBold),
            color =
                if (emphasize) {
                    LocalJellyfinPalette.current.cyan
                } else {
                    TvSecondaryStyle.color
                },
        )
    }
}

@Composable
internal fun TvAudioUnavailableBanner(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(TvDimens.panelRadius))
                .background(LocalJellyfinPalette.current.surfaceNavy.copy(alpha = 0.94f))
                .padding(
                    horizontal = TvDimens.playerPickerPadding,
                    vertical = TvDimens.formGap,
                ),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TvIcons.VolumeOff,
            contentDescription = null,
            tint = LocalJellyfinPalette.current.cyan,
            modifier = Modifier.size(TvDimens.playerIconSize),
        )
        TvText(
            text = stringResource(R.string.tv_player_audio_unavailable),
            style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
        )
    }
}

@Composable
internal fun TvSubtitleUnavailableBanner(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(TvDimens.panelRadius))
                .background(LocalJellyfinPalette.current.surfaceNavy.copy(alpha = 0.94f))
                .padding(
                    horizontal = TvDimens.playerPickerPadding,
                    vertical = TvDimens.formGap,
                ),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TvIcons.Subtitles,
            contentDescription = null,
            tint = LocalJellyfinPalette.current.error,
            modifier = Modifier.size(TvDimens.playerIconSize),
        )
        TvText(
            text = stringResource(R.string.tv_player_subtitle_unavailable),
            style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
        )
    }
}

@Composable
internal fun TvBackendFallbackBanner(
    notice: PlayerBackendNotice,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(TvDimens.panelRadius))
                .background(LocalJellyfinPalette.current.surfaceNavy.copy(alpha = 0.94f))
                .padding(
                    horizontal = TvDimens.playerPickerPadding,
                    vertical = TvDimens.formGap,
                ),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText(
            text =
                stringResource(
                    R.string.tv_player_backend_fallback,
                    playerBackendLabel(notice.requested),
                    playerBackendLabel(notice.active),
                ),
            style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
        )
    }
}

@Composable
internal fun TvPlaybackChangeKeptBanner(
    notice: PlayerPlaybackChangeNotice,
    onAction: (PlaybackAction) -> Unit,
    onFocusedDismiss: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRestorer = remember { TvPlaybackGuidanceFocusRestorer() }
    val currentOnFocusedDismiss = rememberUpdatedState(onFocusedDismiss)
    DisposableEffect(focusRestorer) {
        onDispose {
            focusRestorer.restoreFocusIfActionWasFocusedThen(currentOnFocusedDismiss.value)
        }
    }
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(TvDimens.panelRadius))
                .background(LocalJellyfinPalette.current.surfaceNavy.copy(alpha = 0.94f))
                .padding(
                    horizontal = TvDimens.playerPickerPadding,
                    vertical = TvDimens.formGap,
                ),
        verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TvIcons.InformationOutline,
                contentDescription = null,
                tint = LocalJellyfinPalette.current.cyan,
                modifier = Modifier.size(TvDimens.playerIconSize),
            )
            TvText(
                text = tvPlaybackChangeMessage(notice),
                style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 3,
            )
        }
        TvButton(
            text = stringResource(R.string.tv_dismiss),
            onClick = {
                val handedOff =
                    focusRestorer.restoreFocusIfActionWasFocusedThen(
                        onFocusedDismiss = currentOnFocusedDismiss.value,
                        afterFocus = { onAction(PlaybackAction.Dismiss) },
                    )
                if (!handedOff) {
                    onAction(PlaybackAction.Dismiss)
                }
            },
            modifier =
                Modifier.onFocusChanged { focusState ->
                    focusRestorer.onActionFocusChanged(focusState.isFocused)
                },
        )
    }
}

@Composable
private fun tvPlaybackChangeMessage(notice: PlayerPlaybackChangeNotice): String {
    val reason =
        stringResource(
            when (notice.error) {
                PlaybackError.Network -> R.string.tv_player_change_reason_network
                PlaybackError.UnsupportedMedia -> R.string.tv_player_change_reason_unsupported
                else -> R.string.tv_player_change_reason_unknown
            },
        )
    return stringResource(
        when (notice.operation) {
            PlayerPlaybackChangeOperation.Quality -> R.string.tv_player_change_quality_rejected
            PlayerPlaybackChangeOperation.Backend -> R.string.tv_player_change_backend_rejected
        },
        reason,
    )
}

@Composable
internal fun TvPlaybackActionNotice(
    notice: PlaybackActionNotice,
    onAction: (PlaybackAction) -> Unit,
    onFocusedDismiss: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRestorer = remember { TvPlaybackGuidanceFocusRestorer() }
    val currentOnFocusedDismiss = rememberUpdatedState(onFocusedDismiss)
    DisposableEffect(focusRestorer) {
        onDispose {
            focusRestorer.restoreFocusIfActionWasFocusedThen(currentOnFocusedDismiss.value)
        }
    }
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(TvDimens.panelRadius))
                .background(Color.Black.copy(alpha = TV_DEBUG_OVERLAY_ALPHA))
                .padding(horizontal = TvDimens.playerPickerPadding, vertical = TvDimens.formGap),
        verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvText(
            text =
                stringResource(
                    when (notice.reason) {
                        PlaybackActionNoticeReason.OriginalPlaybackFailed -> R.string.tv_player_action_original_failed
                        PlaybackActionNoticeReason.FixedQualityFailed -> R.string.tv_player_action_fixed_failed
                        PlaybackActionNoticeReason.QualityRecoveryApplied -> R.string.tv_player_action_recovery_applied
                        PlaybackActionNoticeReason.CompatibilityRecoveryExhausted,
                        PlaybackActionNoticeReason.NoLowerQualityAvailable,
                        -> R.string.tv_player_action_recovery_exhausted
                    },
                ),
            style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap)) {
            playbackActionOrder.forEach { action ->
                if (action !in notice.actions) return@forEach
                TvButton(
                    text = stringResource(action.tvActionLabel()),
                    onClick = {
                        val handedOff =
                            focusRestorer.restoreFocusIfActionWasFocusedThen(
                                onFocusedDismiss = currentOnFocusedDismiss.value,
                                afterFocus = { onAction(action) },
                            )
                        if (!handedOff) {
                            onAction(action)
                        }
                    },
                    modifier =
                        Modifier
                            .onFocusChanged { focusState ->
                                focusRestorer.onActionFocusChanged(focusState.isFocused)
                            },
                )
            }
        }
    }
}

@Composable
internal fun TvPlaybackGuidanceNotice(
    guidance: PlaybackHealthGuidance,
    onDismiss: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    onFocusedDismiss: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = tvPlaybackGuidanceMessage(guidance)
    val focusRestorer = remember { TvPlaybackGuidanceFocusRestorer() }
    val currentOnFocusedDismiss = rememberUpdatedState(onFocusedDismiss)
    DisposableEffect(focusRestorer) {
        onDispose {
            focusRestorer.restoreFocusIfActionWasFocusedThen(currentOnFocusedDismiss.value)
        }
    }
    Column(
        modifier =
            modifier
                .semantics { contentDescription = message }
                .clip(RoundedCornerShape(TvDimens.panelRadius))
                .background(Color.Black.copy(alpha = TV_DEBUG_OVERLAY_ALPHA))
                .padding(
                    horizontal = TvDimens.playerPickerPadding,
                    vertical = TvDimens.formGap,
                ),
        verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvText(
            text = message,
            style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap)) {
            if (guidance.canOpenPlaybackSettings) {
                TvButton(
                    text = stringResource(R.string.tv_player_action_open_settings),
                    onClick = {
                        val handedOff =
                            focusRestorer.restoreFocusIfActionWasFocusedThen(
                                onFocusedDismiss = currentOnFocusedDismiss.value,
                                afterFocus = onOpenPlaybackSettings,
                            )
                        if (!handedOff) {
                            onOpenPlaybackSettings()
                        }
                    },
                    modifier =
                        Modifier
                            .onFocusChanged { focusState ->
                                focusRestorer.onActionFocusChanged(focusState.isFocused)
                            },
                )
            }
            if (guidance.canDismiss) {
                TvButton(
                    text = stringResource(R.string.tv_dismiss),
                    onClick = {
                        val handedOff =
                            focusRestorer.restoreFocusIfActionWasFocusedThen(
                                onFocusedDismiss = currentOnFocusedDismiss.value,
                                afterFocus = onDismiss,
                            )
                        if (!handedOff) {
                            onDismiss()
                        }
                    },
                    modifier =
                        Modifier
                            .onFocusChanged { focusState ->
                                focusRestorer.onActionFocusChanged(focusState.isFocused)
                            },
                )
            }
        }
    }
}

internal class TvPlaybackGuidanceFocusRestorer {
    private var actionFocused = false
    private var focusRestored = false

    fun onActionFocusChanged(isFocused: Boolean) {
        actionFocused = isFocused
        if (isFocused) {
            focusRestored = false
        }
    }

    fun restoreFocusIfActionWasFocused(onFocusedDismiss: () -> Unit) {
        restoreFocusIfActionWasFocusedThen(
            onFocusedDismiss = { _ -> onFocusedDismiss() },
        )
    }

    fun restoreFocusIfActionWasFocusedThen(
        onFocusedDismiss: (() -> Unit) -> Unit,
        afterFocus: () -> Unit = {},
    ): Boolean {
        if (!actionFocused || focusRestored) {
            return false
        }
        focusRestored = true
        onFocusedDismiss(afterFocus)
        return true
    }
}

private val playbackActionOrder =
    listOf(
        PlaybackAction.AcceptAuto,
        PlaybackAction.ClearQualityOverride,
        PlaybackAction.KeepCurrentQuality,
        PlaybackAction.ChooseLowerQuality,
        PlaybackAction.TryHigherQuality,
        PlaybackAction.TryOriginal,
        PlaybackAction.Retry,
        PlaybackAction.OpenPlaybackSettings,
        PlaybackAction.Dismiss,
        PlaybackAction.Close,
    )

private fun PlaybackAction.tvActionLabel(): Int =
    when (this) {
        PlaybackAction.AcceptAuto,
        PlaybackAction.ClearQualityOverride,
        -> R.string.tv_player_action_accept_auto
        PlaybackAction.KeepCurrentQuality -> R.string.tv_player_action_keep_quality
        PlaybackAction.ChooseLowerQuality -> R.string.tv_player_action_choose_lower
        PlaybackAction.TryHigherQuality -> R.string.tv_player_action_try_higher
        PlaybackAction.TryOriginal -> R.string.tv_player_action_try_original
        PlaybackAction.Retry -> R.string.tv_player_action_retry
        PlaybackAction.OpenPlaybackSettings -> R.string.tv_player_action_open_settings
        PlaybackAction.Dismiss -> R.string.tv_dismiss
        PlaybackAction.Close -> R.string.tv_player_action_close
    }

@Composable
private fun tvPlaybackGuidanceMessage(guidance: PlaybackHealthGuidance): String {
    val reason =
        stringResource(
            when (guidance.reason) {
                PlaybackHealthGuidanceReason.SlowStartup -> R.string.tv_player_guidance_slow_startup
                PlaybackHealthGuidanceReason.LongBuffering -> R.string.tv_player_guidance_long_buffering
                PlaybackHealthGuidanceReason.CumulativeBuffering -> R.string.tv_player_guidance_cumulative_buffering
                PlaybackHealthGuidanceReason.RepeatedStalls -> R.string.tv_player_guidance_repeated_stalls
                PlaybackHealthGuidanceReason.DroppedFrames -> R.string.tv_player_guidance_dropped_frames
                PlaybackHealthGuidanceReason.NoVideoOutput -> R.string.tv_player_guidance_no_video_output
                PlaybackHealthGuidanceReason.RecoveredPlaybackFailure -> R.string.tv_player_guidance_recovered_failure
            },
        )
    if (guidance.reason == PlaybackHealthGuidanceReason.SlowStartup) {
        return reason
    }
    val hintRes =
        when (guidance.streamMode) {
            StreamMode.DirectPlay -> R.string.tv_player_guidance_direct_play_hint
            StreamMode.DirectStream, StreamMode.Transcode -> R.string.tv_player_guidance_streaming_pressure_hint
            StreamMode.Offline, null -> R.string.tv_player_guidance_unknown_hint
        }
    return "$reason ${stringResource(hintRes)}"
}

@Composable
internal fun TvAudioUnavailableGlyph(modifier: Modifier = Modifier) {
    val message = stringResource(R.string.tv_player_audio_unavailable)
    Box(
        modifier =
            modifier
                .size(TvDimens.playerOptionButtonSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .semantics { contentDescription = message },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = TvIcons.VolumeOff,
            contentDescription = null,
            tint = LocalJellyfinPalette.current.textPrimary,
            modifier = Modifier.size(TvDimens.playerIconSize),
        )
    }
}

@Composable
internal fun TvPlayerError(
    retryable: Boolean,
    error: PlaybackError?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryActionRequester = remember { FocusRequester() }
    // Focus the primary action as soon as the dialog appears so the remote's
    // select button acts on it without an extra click (Retry when retryable,
    // otherwise Cancel).
    LaunchedEffect(retryable) {
        withFrameNanos { }
        primaryActionRequester.requestFocusSafely()
    }
    Column(
        modifier =
            modifier
                .background(LocalJellyfinPalette.current.surfaceNavy.copy(alpha = 0.9f))
                .padding(TvDimens.playerPickerPadding),
        verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvText(
            text = stringResource(tvPlayerErrorMessage(error)),
            color = LocalJellyfinPalette.current.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.formGap)) {
            if (retryable) {
                TvButton(
                    text = stringResource(R.string.tv_retry),
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(primaryActionRequester),
                )
            }
            TvButton(
                text = stringResource(R.string.tv_cancel),
                onClick = onCancel,
                modifier =
                    if (retryable) {
                        Modifier
                    } else {
                        Modifier.focusRequester(primaryActionRequester)
                    },
            )
        }
    }
}

private fun tvPlayerErrorMessage(error: PlaybackError?): Int =
    when (error) {
        PlaybackError.AudioOutput -> R.string.tv_player_error_audio_output
        PlaybackError.Decoder -> R.string.tv_player_error_decoder
        PlaybackError.Network -> R.string.tv_player_error_network
        PlaybackError.UnsupportedMedia -> R.string.tv_player_error_unsupported
        PlaybackError.OfflineArtifactUnavailable -> R.string.tv_player_error_offline_artifact
        PlaybackError.Drm -> R.string.tv_player_error_drm
        is PlaybackError.OfflinePlayerUnavailable -> R.string.tv_player_error_offline_player
        PlaybackError.Unknown,
        null,
        -> R.string.tv_player_error
    }

@Composable
internal fun TvSoftwarePlaybackRecoveryDialog(
    prompt: PlayerSoftwarePlaybackRecovery,
    onSwitch: () -> Unit,
    onContinue: () -> Unit,
    onStop: () -> Unit,
) {
    val primary = remember { FocusRequester() }
    val switchEnabled = prompt.canSwitch && !prompt.switching
    val continueEnabled = prompt.canContinue && !prompt.switching
    BackHandler { }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(prompt.token, switchEnabled, continueEnabled) {
            withFrameNanos { }
            primary.requestFocusSafely()
        }
        Column(
            modifier =
                Modifier
                    .width(TvDimens.settingsDialogWidth)
                    .heightIn(max = TvDimens.settingsDialogMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .background(LocalJellyfinPalette.current.surfaceNavy, RoundedCornerShape(TvDimens.panelRadius))
                    .padding(TvDimens.playerPickerPadding)
                    .onPreviewKeyEvent { event ->
                        event.key in
                            listOf(
                                Key.Back,
                                Key.MediaPlay,
                                Key.MediaPause,
                                Key.MediaPlayPause,
                                Key.MediaNext,
                                Key.MediaPrevious,
                                Key.MediaFastForward,
                                Key.MediaRewind,
                                Key.MediaSkipForward,
                                Key.MediaSkipBackward,
                            )
                    },
            verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
        ) {
            TvText(text = stringResource(R.string.tv_player_software_recovery_title), style = TvPlayerSectionTitleStyle, maxLines = 2)
            TvText(text = stringResource(R.string.tv_player_software_recovery_message), maxLines = 6)
            when {
                prompt.switchFailed -> TvText(text = stringResource(R.string.tv_player_software_recovery_failed), maxLines = 4)
                !prompt.canSwitch -> TvText(text = stringResource(R.string.tv_player_software_recovery_unavailable), maxLines = 3)
            }
            TvButton(
                text = stringResource(R.string.tv_player_software_recovery_switch),
                onClick = onSwitch,
                enabled = switchEnabled,
                modifier = Modifier.fillMaxWidth().then(if (switchEnabled) Modifier.focusRequester(primary) else Modifier),
            )
            TvButton(
                text = stringResource(R.string.tv_player_software_recovery_continue),
                onClick = onContinue,
                enabled = continueEnabled,
                modifier =
                    Modifier.fillMaxWidth().then(
                        if (!switchEnabled &&
                            continueEnabled
                        ) {
                            Modifier.focusRequester(primary)
                        } else {
                            Modifier
                        },
                    ),
            )
            TvButton(
                text = stringResource(R.string.tv_player_software_recovery_stop),
                onClick = onStop,
                modifier =
                    Modifier.fillMaxWidth().then(
                        if (!switchEnabled &&
                            !continueEnabled
                        ) {
                            Modifier.focusRequester(primary)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}
