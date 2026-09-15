// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNotice
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthGuidanceReason
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_retry
import com.jellyscope.ui.generated.resources.player_action_accept_auto
import com.jellyscope.ui.generated.resources.player_action_choose_lower
import com.jellyscope.ui.generated.resources.player_action_close
import com.jellyscope.ui.generated.resources.player_action_fixed_failed
import com.jellyscope.ui.generated.resources.player_action_keep_quality
import com.jellyscope.ui.generated.resources.player_action_original_failed
import com.jellyscope.ui.generated.resources.player_action_recovery_applied
import com.jellyscope.ui.generated.resources.player_action_recovery_exhausted
import com.jellyscope.ui.generated.resources.player_action_retry
import com.jellyscope.ui.generated.resources.player_action_try_higher
import com.jellyscope.ui.generated.resources.player_action_try_original
import com.jellyscope.ui.generated.resources.player_audio_unavailable
import com.jellyscope.ui.generated.resources.player_backend_fallback
import com.jellyscope.ui.generated.resources.player_change_backend_rejected
import com.jellyscope.ui.generated.resources.player_change_quality_rejected
import com.jellyscope.ui.generated.resources.player_change_reason_network
import com.jellyscope.ui.generated.resources.player_change_reason_unknown
import com.jellyscope.ui.generated.resources.player_change_reason_unsupported
import com.jellyscope.ui.generated.resources.player_debug_overlay
import com.jellyscope.ui.generated.resources.player_dismiss
import com.jellyscope.ui.generated.resources.player_error
import com.jellyscope.ui.generated.resources.player_error_audio_output
import com.jellyscope.ui.generated.resources.player_error_decoder
import com.jellyscope.ui.generated.resources.player_error_drm
import com.jellyscope.ui.generated.resources.player_error_network
import com.jellyscope.ui.generated.resources.player_error_offline_artifact
import com.jellyscope.ui.generated.resources.player_error_offline_player
import com.jellyscope.ui.generated.resources.player_error_title
import com.jellyscope.ui.generated.resources.player_error_unsupported
import com.jellyscope.ui.generated.resources.player_guidance_cumulative_buffering
import com.jellyscope.ui.generated.resources.player_guidance_direct_play_hint
import com.jellyscope.ui.generated.resources.player_guidance_dropped_frames
import com.jellyscope.ui.generated.resources.player_guidance_long_buffering
import com.jellyscope.ui.generated.resources.player_guidance_no_video_output
import com.jellyscope.ui.generated.resources.player_guidance_open_playback_settings
import com.jellyscope.ui.generated.resources.player_guidance_recovered_failure
import com.jellyscope.ui.generated.resources.player_guidance_reduce_quality
import com.jellyscope.ui.generated.resources.player_guidance_repeated_stalls
import com.jellyscope.ui.generated.resources.player_guidance_slow_startup
import com.jellyscope.ui.generated.resources.player_guidance_streaming_pressure_hint
import com.jellyscope.ui.generated.resources.player_guidance_unknown_hint
import com.jellyscope.ui.generated.resources.player_picker_close
import com.jellyscope.ui.generated.resources.player_software_recovery_continue
import com.jellyscope.ui.generated.resources.player_software_recovery_failed
import com.jellyscope.ui.generated.resources.player_software_recovery_message
import com.jellyscope.ui.generated.resources.player_software_recovery_stop
import com.jellyscope.ui.generated.resources.player_software_recovery_switch
import com.jellyscope.ui.generated.resources.player_software_recovery_title
import com.jellyscope.ui.generated.resources.player_software_recovery_unavailable
import com.jellyscope.ui.generated.resources.player_subtitle_unavailable
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AudioUnavailableBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = stringResource(Res.string.player_audio_unavailable)
    Surface(
        modifier = modifier.widthIn(max = Dimensions.playerDialogMaxWidth),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = PLAYER_NOTICE_SURFACE_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(Dimensions.contentSpacing),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.playerControlIconSize),
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(Dimensions.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.player_dismiss),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun AudioUnavailableGlyph(modifier: Modifier = Modifier) {
    val message = stringResource(Res.string.player_audio_unavailable)
    Box(
        modifier =
            modifier
                .size(Dimensions.minTouchTarget)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = PLAYER_CONTROL_BACKGROUND_ALPHA))
                .semantics { contentDescription = message },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(Dimensions.playerControlIconSize),
        )
    }
}

@Composable
internal fun SubtitleUnavailableBanner(
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = Dimensions.playerDialogMaxWidth),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = PLAYER_NOTICE_SURFACE_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(Dimensions.contentSpacing),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Subtitles,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(Dimensions.playerControlIconSize),
            )
            Text(
                text = message ?: stringResource(Res.string.player_subtitle_unavailable),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun BackendFallbackBanner(
    notice: PlayerBackendNotice,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = Dimensions.playerDialogMaxWidth),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = PLAYER_NOTICE_SURFACE_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(Dimensions.contentSpacing),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.playerControlIconSize),
            )
            Text(
                text =
                    stringResource(
                        Res.string.player_backend_fallback,
                        playerBackendLabel(notice.requested),
                        playerBackendLabel(notice.active),
                    ),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PlaybackChangeKeptBanner(
    notice: PlayerPlaybackChangeNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .widthIn(max = Dimensions.playerDialogMaxWidth)
                .fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = PLAYER_NOTICE_SURFACE_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.contentSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimensions.playerControlIconSize),
                )
                Text(
                    text = playbackChangeMessage(notice),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.player_dismiss))
                }
            }
        }
    }
}

@Composable
private fun playbackChangeMessage(notice: PlayerPlaybackChangeNotice): String {
    val reason =
        stringResource(
            when (notice.error) {
                PlaybackError.Network -> Res.string.player_change_reason_network
                PlaybackError.UnsupportedMedia -> Res.string.player_change_reason_unsupported
                else -> Res.string.player_change_reason_unknown
            },
        )
    return stringResource(
        when (notice.operation) {
            PlayerPlaybackChangeOperation.Quality -> Res.string.player_change_quality_rejected
            PlayerPlaybackChangeOperation.Backend -> Res.string.player_change_backend_rejected
        },
        reason,
    )
}

@Composable
internal fun PlaybackGuidanceBanner(
    guidance: PlaybackHealthGuidance,
    onReduce: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = playbackGuidanceActions(guidance)
    Surface(
        modifier =
            modifier
                .widthIn(max = Dimensions.playerDialogMaxWidth)
                .fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = Color.Black.copy(alpha = PLAYER_DEBUG_OVERLAY_ALPHA),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.contentSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimensions.playerControlIconSize),
                )
                Text(
                    text = playerPlaybackGuidanceMessage(guidance),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            ) {
                if (actions.reduceQuality) {
                    TextButton(
                        onClick = onReduce,
                    ) {
                        Text(stringResource(Res.string.player_guidance_reduce_quality))
                    }
                }
                if (actions.navigateToSettings) {
                    TextButton(
                        onClick = onOpenPlaybackSettings,
                    ) {
                        Text(stringResource(Res.string.player_guidance_open_playback_settings))
                    }
                }
                if (actions.dismiss) {
                    IconButton(
                        onClick = onDismiss,
                        modifier =
                            Modifier
                                .size(Dimensions.minTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.player_dismiss),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaybackActionNoticeBanner(
    notice: PlaybackActionNotice,
    onAction: (PlaybackAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .widthIn(max = Dimensions.playerDialogMaxWidth)
                .fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = Color.Black.copy(alpha = PLAYER_DEBUG_OVERLAY_ALPHA),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.contentSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimensions.playerControlIconSize),
                )
                Text(
                    text = actionNoticeMessage(notice),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            ) {
                notice.actions.forEach { action ->
                    val label = actionNoticeActionLabel(action) ?: return@forEach
                    TextButton(
                        onClick = { onAction(action) },
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun actionNoticeMessage(notice: PlaybackActionNotice): String =
    stringResource(
        when (notice.reason) {
            PlaybackActionNoticeReason.QualityRecoveryApplied -> Res.string.player_action_recovery_applied
            PlaybackActionNoticeReason.OriginalPlaybackFailed -> Res.string.player_action_original_failed
            PlaybackActionNoticeReason.FixedQualityFailed -> Res.string.player_action_fixed_failed
            PlaybackActionNoticeReason.CompatibilityRecoveryExhausted,
            PlaybackActionNoticeReason.NoLowerQualityAvailable,
            -> Res.string.player_action_recovery_exhausted
        },
    )

@Composable
private fun actionNoticeActionLabel(action: PlaybackAction): String? =
    when (action) {
        PlaybackAction.AcceptAuto -> stringResource(Res.string.player_action_accept_auto)
        PlaybackAction.KeepCurrentQuality -> stringResource(Res.string.player_action_keep_quality)
        PlaybackAction.ChooseLowerQuality -> stringResource(Res.string.player_action_choose_lower)
        PlaybackAction.TryHigherQuality -> stringResource(Res.string.player_action_try_higher)
        PlaybackAction.TryOriginal -> stringResource(Res.string.player_action_try_original)
        PlaybackAction.Retry -> stringResource(Res.string.player_action_retry)
        PlaybackAction.Close -> stringResource(Res.string.player_action_close)
        PlaybackAction.ClearQualityOverride -> stringResource(Res.string.player_action_accept_auto)
        PlaybackAction.OpenPlaybackSettings -> stringResource(Res.string.player_guidance_open_playback_settings)
        PlaybackAction.Dismiss -> stringResource(Res.string.player_dismiss)
    }

@Composable
private fun playerPlaybackGuidanceMessage(guidance: PlaybackHealthGuidance): String {
    val reason =
        stringResource(
            when (guidance.reason) {
                PlaybackHealthGuidanceReason.SlowStartup -> Res.string.player_guidance_slow_startup
                PlaybackHealthGuidanceReason.LongBuffering -> Res.string.player_guidance_long_buffering
                PlaybackHealthGuidanceReason.CumulativeBuffering -> Res.string.player_guidance_cumulative_buffering
                PlaybackHealthGuidanceReason.RepeatedStalls -> Res.string.player_guidance_repeated_stalls
                PlaybackHealthGuidanceReason.DroppedFrames -> Res.string.player_guidance_dropped_frames
                PlaybackHealthGuidanceReason.NoVideoOutput -> Res.string.player_guidance_no_video_output
                PlaybackHealthGuidanceReason.RecoveredPlaybackFailure -> Res.string.player_guidance_recovered_failure
            },
        )
    if (guidance.reason == PlaybackHealthGuidanceReason.SlowStartup) {
        return reason
    }
    val hint =
        stringResource(
            when (guidance.wordingPolicy()) {
                PlayerPlaybackGuidanceWording.SlowStartup -> Res.string.player_guidance_slow_startup
                PlayerPlaybackGuidanceWording.DirectPlayCapacity -> Res.string.player_guidance_direct_play_hint
                PlayerPlaybackGuidanceWording.StreamingPressure -> Res.string.player_guidance_streaming_pressure_hint
                PlayerPlaybackGuidanceWording.PlaybackTakingLonger -> Res.string.player_guidance_unknown_hint
            },
        )
    return "$reason $hint"
}

@Composable
internal fun PlayerError(
    retryable: Boolean,
    error: PlaybackError?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onBack,
        modifier =
            modifier.widthIn(
                min = Dimensions.playerDialogMinWidth,
                max = Dimensions.playerDialogMaxWidth,
            ),
        title = {
            Text(
                text = stringResource(Res.string.player_error_title),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(playerErrorMessage(error)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            if (retryable) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(Res.string.detail_retry))
                }
            } else {
                TextButton(onClick = onBack) {
                    Text(stringResource(Res.string.player_picker_close))
                }
            }
        },
        dismissButton =
            if (retryable) {
                {
                    TextButton(onClick = onBack) {
                        Text(stringResource(Res.string.player_picker_close))
                    }
                }
            } else {
                null
            },
    )
}

private fun playerErrorMessage(error: PlaybackError?): StringResource =
    when (error) {
        PlaybackError.AudioOutput -> Res.string.player_error_audio_output
        PlaybackError.Decoder -> Res.string.player_error_decoder
        PlaybackError.Network -> Res.string.player_error_network
        PlaybackError.UnsupportedMedia -> Res.string.player_error_unsupported
        PlaybackError.OfflineArtifactUnavailable -> Res.string.player_error_offline_artifact
        PlaybackError.Drm -> Res.string.player_error_drm
        is PlaybackError.OfflinePlayerUnavailable -> Res.string.player_error_offline_player
        PlaybackError.Unknown,
        null,
        -> Res.string.player_error
    }

@Composable
internal fun BufferingIndicator(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    CircularProgressIndicator(
        modifier =
            modifier.size(
                if (compact) {
                    Dimensions.playerBufferingIndicatorSizeCompact
                } else {
                    Dimensions.playerBufferingIndicatorSize
                },
            ),
        strokeWidth =
            if (compact) {
                Dimensions.playerBufferingIndicatorStrokeCompact
            } else {
                Dimensions.playerBufferingIndicatorStroke
            },
    )
}

@Composable
internal fun PlayerDebugOverlay(
    debugInfo: PlayerDebugInfo?,
    playbackStateFlow: StateFlow<PlaybackState>,
    runtimeDiagnosticsFlow: StateFlow<PlaybackRuntimeDiagnostics>,
    modifier: Modifier = Modifier,
    // Desktop uses the caller-sized panel to avoid truncating rows.
    expanded: Boolean = false,
    compact: Boolean = false,
) {
    val playbackState by playbackStateFlow.collectAsStateWithLifecycle()
    val runtimeDiagnostics by runtimeDiagnosticsFlow.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    Surface(
        modifier =
            if (expanded) {
                modifier
            } else if (compact) {
                modifier.fillMaxWidth()
            } else {
                modifier
                    .widthIn(max = Dimensions.playerOverlayCardWidth)
            },
        color = Color.Black.copy(alpha = PLAYER_DEBUG_OVERLAY_ALPHA),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier =
                Modifier
                    .then(
                        if (expanded) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight)
                        },
                    ).verticalScroll(scrollState)
                    .padding(Dimensions.formSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.badgeVerticalPadding),
        ) {
            Text(
                text = stringResource(Res.string.player_debug_overlay),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            playerDebugSections(
                debugInfo = debugInfo,
                playbackState = playbackState,
                runtimeDiagnostics = runtimeDiagnostics,
                mpvLabels = playerDebugMpvLabels(),
            ).flatMap(PlayerDebugSection::rows)
                .forEach { row ->
                    DebugRow(
                        label = row.label,
                        value = row.value,
                        emphasize = row.emphasize,
                    )
                }
        }
    }
}

@Composable
private fun DebugRow(
    label: String,
    value: String,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = PLAYER_DEBUG_LABEL_ALPHA),
            modifier = Modifier.weight(PLAYER_DEBUG_LABEL_WEIGHT),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color =
                if (emphasize) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    Color.White
                },
            modifier =
                Modifier
                    .weight(PLAYER_DEBUG_VALUE_WEIGHT),
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SoftwarePlaybackRecoveryDialog(
    prompt: PlayerSoftwarePlaybackRecovery,
    onSwitch: () -> Unit,
    onContinue: () -> Unit,
    onStop: () -> Unit,
) {
    BackHandler { }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(Res.string.player_software_recovery_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
            ) {
                Text(stringResource(Res.string.player_software_recovery_message))
                when {
                    prompt.switchFailed -> Text(stringResource(Res.string.player_software_recovery_failed))
                    !prompt.canSwitch -> Text(stringResource(Res.string.player_software_recovery_unavailable))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSwitch, enabled = prompt.canSwitch && !prompt.switching) {
                Text(stringResource(Res.string.player_software_recovery_switch))
            }
        },
        dismissButton = {
            FlowRow {
                TextButton(onClick = onContinue, enabled = prompt.canContinue && !prompt.switching) {
                    Text(stringResource(Res.string.player_software_recovery_continue))
                }
                TextButton(onClick = onStop) { Text(stringResource(Res.string.player_software_recovery_stop)) }
            }
        },
    )
}
