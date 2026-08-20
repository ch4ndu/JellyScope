// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.formatBitrateMbps
import com.jellyscope.core.domain.playback.qualityRungForBitrate
import com.jellyscope.core.domain.playback.vlcTranscodeBudgetOptions
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_quality_original
import com.jellyscope.ui.generated.resources.settings_audio_formats
import com.jellyscope.ui.generated.resources.settings_audio_language
import com.jellyscope.ui.generated.resources.settings_autoplay_delay
import com.jellyscope.ui.generated.resources.settings_autoplay_next
import com.jellyscope.ui.generated.resources.settings_max_bitrate
import com.jellyscope.ui.generated.resources.settings_picker_audio_language_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_autoplay_delay_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_bitrate_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_player_backend_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_segment_policy_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_subtitle_language_subtitle
import com.jellyscope.ui.generated.resources.settings_picture_in_picture
import com.jellyscope.ui.generated.resources.settings_playback_audio_language
import com.jellyscope.ui.generated.resources.settings_playback_autoplay_delay_immediate
import com.jellyscope.ui.generated.resources.settings_playback_autoplay_delay_seconds
import com.jellyscope.ui.generated.resources.settings_playback_bitrate_auto
import com.jellyscope.ui.generated.resources.settings_playback_bitrate_custom
import com.jellyscope.ui.generated.resources.settings_playback_bitrate_mbps
import com.jellyscope.ui.generated.resources.settings_playback_bitrate_resolution
import com.jellyscope.ui.generated.resources.settings_playback_error
import com.jellyscope.ui.generated.resources.settings_playback_loading
import com.jellyscope.ui.generated.resources.settings_playback_preferences
import com.jellyscope.ui.generated.resources.settings_playback_retry
import com.jellyscope.ui.generated.resources.settings_playback_subtitle_language
import com.jellyscope.ui.generated.resources.settings_playback_vlc_budget_disabled
import com.jellyscope.ui.generated.resources.settings_playback_warnings
import com.jellyscope.ui.generated.resources.settings_playback_warnings_description
import com.jellyscope.ui.generated.resources.settings_player_backend_auto
import com.jellyscope.ui.generated.resources.settings_player_backend_avplayer
import com.jellyscope.ui.generated.resources.settings_player_backend_exoplayer
import com.jellyscope.ui.generated.resources.settings_player_backend_libvlc
import com.jellyscope.ui.generated.resources.settings_player_backend_mpv
import com.jellyscope.ui.generated.resources.settings_player_backend_title
import com.jellyscope.ui.generated.resources.settings_player_backend_unavailable
import com.jellyscope.ui.generated.resources.settings_player_backend_vlckit
import com.jellyscope.ui.generated.resources.settings_player_backend_vlckit_warning
import com.jellyscope.ui.generated.resources.settings_player_detected_summary
import com.jellyscope.ui.generated.resources.settings_player_hdr_desktop_notice
import com.jellyscope.ui.generated.resources.settings_player_policy_error
import com.jellyscope.ui.generated.resources.settings_player_refresh_capabilities
import com.jellyscope.ui.generated.resources.settings_player_refresh_complete
import com.jellyscope.ui.generated.resources.settings_section_advanced_playback
import com.jellyscope.ui.generated.resources.settings_section_skip_segments
import com.jellyscope.ui.generated.resources.settings_segment_commercials
import com.jellyscope.ui.generated.resources.settings_segment_credits
import com.jellyscope.ui.generated.resources.settings_segment_intros
import com.jellyscope.ui.generated.resources.settings_segment_previews
import com.jellyscope.ui.generated.resources.settings_segment_recaps
import com.jellyscope.ui.generated.resources.settings_still_watching
import com.jellyscope.ui.generated.resources.settings_subtitle_language
import com.jellyscope.ui.generated.resources.settings_video_formats
import com.jellyscope.ui.generated.resources.settings_vlc_transcode_limit
import com.jellyscope.ui.generated.resources.settings_vlc_transcode_limit_subtitle
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlaybackSettingsSection(
    state: SettingsUiState,
    onReload: () -> Unit,
    onSetDefaultMaxBitrateBps: (Long?) -> Unit,
    onSetDefaultQualityPolicy: (PlaybackQualityPolicy) -> Unit = { policy -> onSetDefaultMaxBitrateBps(policy.maxBitrateBps) },
    onSetVlcTranscodeMaxBitrateBps: (Long?) -> Unit = {},
    onSetDefaultPlayerBackend: (PlayerBackend) -> Unit,
    onSetPreferredAudioLanguage: (String?) -> Unit,
    onSetPreferredSubtitleLanguage: (String?) -> Unit,
    onSetAutoPlayNext: (Boolean) -> Unit,
    onSetStillWatchingPrompt: (Boolean) -> Unit,
    onSetPlaybackWarningsEnabled: (Boolean) -> Unit,
    onSetAutoPlayNextDelaySeconds: (Int) -> Unit,
    openRow: SettingsRowId?,
    onOpenRow: (SettingsRowId) -> Unit,
    onDismiss: () -> Unit,
) {
    val preferences = state.playbackPreferences
    val selectedQualityPolicy = preferences.effectiveDefaultQualityPolicy()
    // The ViewModel owns backend order and availability.
    val backendChoices = state.playerBackendChoices
    val selectedBackend = state.selectedPlayerBackend
    val bitrateOptions =
        playbackQualitySettingChoices(selectedQualityPolicy).map { choice ->
            SettingsPickerOption(
                value = qualityPolicy(choice),
                label = qualityLabel(choice),
            )
        }
    val vlcBudgetVisible =
        backendChoices.any { choice ->
            choice.backend == PlayerBackend.LibVlc || choice.backend == PlayerBackend.VlcKit
        }
    val vlcBudgetOptions =
        vlcTranscodeBudgetOptions.map { bitrate ->
            SettingsPickerOption(
                value = bitrate,
                label = vlcBudgetLabel(bitrate),
            )
        }
    val autoplayDelayPickerOptions =
        autoplayDelayOptions.map { delaySeconds ->
            SettingsPickerOption(value = delaySeconds, label = autoplayDelayLabel(delaySeconds))
        }
    val backendUnavailableReason = stringResource(Res.string.settings_player_backend_unavailable)
    val backendOptions =
        backendChoices.map { choice ->
            SettingsPickerOption(
                value = choice.backend,
                label = playerBackendLabel(choice.backend),
                supporting =
                    stringResource(Res.string.settings_player_backend_vlckit_warning)
                        .takeIf { choice.backend == PlayerBackend.VlcKit },
                disabledReason = backendUnavailableReason.takeUnless { choice.available },
            )
        }
    val header: (@Composable () -> Unit)? =
        if (state.isLoadingPlaybackPreferences || state.playbackPreferencesError) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                    if (state.isLoadingPlaybackPreferences) {
                        LoadingPlaybackPreferences()
                    }
                    if (state.playbackPreferencesError) {
                        PlaybackPreferencesError(onReload = onReload)
                    }
                }
            }
        } else {
            null
        }

    val rows =
        buildList<@Composable () -> Unit> {
            add {
                SettingsRow(
                    icon = SettingsRowId.AudioLanguage.icon(),
                    iconRole = SettingsRowId.AudioLanguage.iconRole(),
                    title = stringResource(Res.string.settings_audio_language),
                    value =
                        preferences.preferredAudioLanguage.takeUnless { it.isNullOrBlank() }
                            ?: stringResource(Res.string.settings_playback_bitrate_auto),
                    trailing = SettingsRowTrailing.Chevron,
                    onClick = { onOpenRow(SettingsRowId.AudioLanguage) },
                )
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.SubtitleLanguage.icon(),
                    iconRole = SettingsRowId.SubtitleLanguage.iconRole(),
                    title = stringResource(Res.string.settings_subtitle_language),
                    value =
                        preferences.preferredSubtitleLanguage.takeUnless { it.isNullOrBlank() }
                            ?: stringResource(Res.string.settings_playback_bitrate_auto),
                    trailing = SettingsRowTrailing.Chevron,
                    onClick = { onOpenRow(SettingsRowId.SubtitleLanguage) },
                )
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.MaxBitrate.icon(),
                    iconRole = SettingsRowId.MaxBitrate.iconRole(),
                    title = stringResource(Res.string.settings_max_bitrate),
                    value = qualityLabel(qualitySettingOption(selectedQualityPolicy)),
                    trailing = SettingsRowTrailing.Chevron,
                    onClick = { onOpenRow(SettingsRowId.MaxBitrate) },
                )
            }
            if (vlcBudgetVisible) {
                add {
                    SettingsRow(
                        icon = SettingsRowId.VlcTranscodeLimit.icon(),
                        iconRole = SettingsRowId.VlcTranscodeLimit.iconRole(),
                        title = stringResource(Res.string.settings_vlc_transcode_limit),
                        value = vlcBudgetLabel(preferences.vlcTranscodeMaxBitrateBps),
                        trailing = SettingsRowTrailing.Chevron,
                        onClick = { onOpenRow(SettingsRowId.VlcTranscodeLimit) },
                    )
                }
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.AutoPlayNext.icon(),
                    iconRole = SettingsRowId.AutoPlayNext.iconRole(),
                    title = stringResource(Res.string.settings_autoplay_next),
                    value = settingsBooleanValue(preferences.autoPlayNext),
                    trailing =
                        SettingsRowTrailing.Switch(
                            checked = preferences.autoPlayNext,
                            onCheckedChange = onSetAutoPlayNext,
                        ),
                )
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.StillWatching.icon(),
                    iconRole = SettingsRowId.StillWatching.iconRole(),
                    title = stringResource(Res.string.settings_still_watching),
                    value = settingsBooleanValue(preferences.stillWatchingPrompt),
                    trailing =
                        SettingsRowTrailing.Switch(
                            checked = preferences.stillWatchingPrompt,
                            onCheckedChange = onSetStillWatchingPrompt,
                        ),
                )
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.PlaybackWarnings.icon(),
                    iconRole = SettingsRowId.PlaybackWarnings.iconRole(),
                    title = stringResource(Res.string.settings_playback_warnings),
                    value = settingsBooleanValue(preferences.playbackWarningsEnabled),
                    description = stringResource(Res.string.settings_playback_warnings_description),
                    trailing =
                        SettingsRowTrailing.Switch(
                            checked = preferences.playbackWarningsEnabled,
                            onCheckedChange = onSetPlaybackWarningsEnabled,
                        ),
                )
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.AutoPlayNextDelay.icon(),
                    iconRole = SettingsRowId.AutoPlayNextDelay.iconRole(),
                    title = stringResource(Res.string.settings_autoplay_delay),
                    value = autoplayDelayLabel(preferences.autoPlayNextDelaySeconds),
                    trailing = SettingsRowTrailing.Chevron,
                    onClick = { onOpenRow(SettingsRowId.AutoPlayNextDelay) },
                )
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.PlayerBackend.icon(),
                    iconRole = SettingsRowId.PlayerBackend.iconRole(),
                    title = stringResource(Res.string.settings_player_backend_title),
                    value = playerBackendLabel(selectedBackend),
                    trailing = SettingsRowTrailing.Chevron,
                    onClick = { onOpenRow(SettingsRowId.PlayerBackend) },
                )
            }
        }

    SettingsSectionCard(
        title = stringResource(Res.string.settings_playback_preferences),
        rows = rows,
        header = header,
    )

    if (openRow == SettingsRowId.AudioLanguage) {
        SettingsTextDialog(
            title = stringResource(Res.string.settings_audio_language),
            value = preferences.preferredAudioLanguage.orEmpty(),
            subtitle = stringResource(Res.string.settings_picker_audio_language_subtitle),
            label = stringResource(Res.string.settings_playback_audio_language),
            onSave = { value, dismiss ->
                onSetPreferredAudioLanguage(value.takeIf { it.isNotBlank() })
                dismiss()
            },
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.SubtitleLanguage) {
        SettingsTextDialog(
            title = stringResource(Res.string.settings_subtitle_language),
            value = preferences.preferredSubtitleLanguage.orEmpty(),
            subtitle = stringResource(Res.string.settings_picker_subtitle_language_subtitle),
            label = stringResource(Res.string.settings_playback_subtitle_language),
            onSave = { value, dismiss ->
                onSetPreferredSubtitleLanguage(value.takeIf { it.isNotBlank() })
                dismiss()
            },
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.MaxBitrate) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_max_bitrate),
            subtitle = stringResource(Res.string.settings_picker_bitrate_subtitle),
            options = bitrateOptions,
            selectedValue = selectedQualityPolicy,
            onOptionSelected = onSetDefaultQualityPolicy,
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.VlcTranscodeLimit && vlcBudgetVisible) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_vlc_transcode_limit),
            subtitle = stringResource(Res.string.settings_vlc_transcode_limit_subtitle),
            options = vlcBudgetOptions,
            selectedValue = preferences.vlcTranscodeMaxBitrateBps,
            onOptionSelected = onSetVlcTranscodeMaxBitrateBps,
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.AutoPlayNextDelay) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_autoplay_delay),
            subtitle = stringResource(Res.string.settings_picker_autoplay_delay_subtitle),
            options = autoplayDelayPickerOptions,
            selectedValue = preferences.autoPlayNextDelaySeconds,
            onOptionSelected = onSetAutoPlayNextDelaySeconds,
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.PlayerBackend) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_player_backend_title),
            subtitle = stringResource(Res.string.settings_picker_player_backend_subtitle),
            options = backendOptions,
            selectedValue = selectedBackend,
            onOptionSelected = onSetDefaultPlayerBackend,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun SkipSegmentsSettingsSection(
    preferences: PlaybackPreferences,
    onSetSegmentSkipPolicy: (MediaSegmentType, SegmentSkipPolicy) -> Unit,
    openRow: SettingsRowId?,
    onOpenRow: (SettingsRowId) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsSectionCard(
        title = stringResource(Res.string.settings_section_skip_segments),
        rows =
            segmentSkipTypes.map { type ->
                {
                    val rowId = segmentRowId(type)
                    SettingsRow(
                        icon = rowId.icon(),
                        iconRole = rowId.iconRole(),
                        title = segmentRowTitle(type),
                        value = segmentSkipPolicyLabel(preferences.policyFor(type)),
                        trailing = SettingsRowTrailing.Chevron,
                        onClick = { onOpenRow(rowId) },
                    )
                }
            },
    )

    segmentSkipTypes.forEach { type ->
        val rowId = segmentRowId(type)
        if (openRow == rowId) {
            SettingsOptionPicker(
                title = segmentRowTitle(type),
                subtitle = stringResource(Res.string.settings_picker_segment_policy_subtitle),
                options =
                    segmentSkipPolicyOptions.map { policy ->
                        SettingsPickerOption(value = policy, label = segmentSkipPolicyLabel(policy))
                    },
                selectedValue = preferences.policyFor(type),
                onOptionSelected = { policy -> onSetSegmentSkipPolicy(type, policy) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
internal fun AdvancedPlaybackSettingsSection(
    state: SettingsUiState,
    onSetPlayerAudioMode: (PlayerAudioMode) -> Unit,
    onSetPlayerHdrMode: (PlayerHdrMode) -> Unit,
    onSetMaxVideoResolution: (PlayerVideoResolutionLimit) -> Unit,
    onSetIosPlaybackCompatibilityMode: (IosPlaybackCompatibilityMode) -> Unit,
    onRefreshPlayerDevicePolicy: () -> Unit,
    onSetPictureInPictureEnabled: (Boolean) -> Unit,
    openRow: SettingsRowId?,
    onOpenRow: (SettingsRowId) -> Unit,
    onDismiss: () -> Unit,
) {
    val policy = state.playerDevicePolicy
    val footer: (@Composable () -> Unit)? =
        if (
            state.playerDevicePolicyError ||
            state.playerDevicePolicyRefreshCompletion > 0 ||
            isDesktopHdrToneMapNoticeVisible()
        ) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                    if (state.playerDevicePolicyError) {
                        Text(
                            text = stringResource(Res.string.settings_player_policy_error),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.playerDevicePolicyRefreshCompletion > 0) {
                        Text(
                            text = stringResource(Res.string.settings_player_refresh_complete),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (isDesktopHdrToneMapNoticeVisible()) {
                        Text(
                            text = stringResource(Res.string.settings_player_hdr_desktop_notice),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            null
        }
    val header: @Composable () -> Unit = {
        Text(
            text =
                stringResource(
                    Res.string.settings_player_detected_summary,
                    policy
                        ?.capabilities
                        ?.videoCodecs
                        ?.joinToString(", ")
                        .orUnknown(),
                    policy?.audioCodecs?.joinToString(", ").orUnknown(),
                    policy?.maxAudioChannels?.toString().orUnknown(),
                ),
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
    val rows =
        buildList<@Composable () -> Unit> {
            add {
                PlayerAudioOutputRow(
                    settings = state.playerDeviceSettings,
                    policy = policy,
                    onOpen = { onOpenRow(SettingsRowId.AudioOutput) },
                )
            }
            add {
                PlayerHdrHandlingRow(
                    settings = state.playerDeviceSettings,
                    policy = policy,
                    onOpen = { onOpenRow(SettingsRowId.HdrHandling) },
                )
            }
            if (isDesktopPlayerDeviceSettingsVisible()) {
                add {
                    PlayerMaximumVideoResolutionRow(
                        settings = state.playerDeviceSettings,
                        onOpen = { onOpenRow(SettingsRowId.MaximumVideoResolution) },
                    )
                }
            }
            if (isPlaybackCompatibilityVisible()) {
                add {
                    PlaybackCompatibilityRow(
                        settings = state.playerDeviceSettings,
                        onOpen = { onOpenRow(SettingsRowId.IosPlaybackCompatibility) },
                    )
                }
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.VideoFormats.icon(),
                    iconRole = SettingsRowId.VideoFormats.iconRole(),
                    title = stringResource(Res.string.settings_video_formats),
                    value =
                        policy
                            ?.capabilities
                            ?.videoCodecs
                            ?.joinToString(", ")
                            .orUnknown(),
                    trailing = SettingsRowTrailing.None,
                )
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.AudioFormats.icon(),
                    iconRole = SettingsRowId.AudioFormats.iconRole(),
                    title = stringResource(Res.string.settings_audio_formats),
                    value = policy?.audioCodecs?.joinToString(", ").orUnknown(),
                    trailing = SettingsRowTrailing.None,
                )
            }
            add {
                SettingsRow(
                    icon = SettingsRowId.RefreshCapabilities.icon(),
                    iconRole = SettingsRowId.RefreshCapabilities.iconRole(),
                    title = stringResource(Res.string.settings_player_refresh_capabilities),
                    value =
                        state.isRefreshingPlayerDevicePolicy
                            .takeIf { running -> running }
                            ?.let { stringResource(Res.string.settings_playback_loading) },
                    trailing =
                        SettingsRowTrailing.Progress(
                            running = state.isRefreshingPlayerDevicePolicy,
                        ),
                    onClick = onRefreshPlayerDevicePolicy,
                )
            }
            if (isPictureInPictureSettingVisible()) {
                add {
                    SettingsRow(
                        icon = SettingsRowId.PictureInPicture.icon(),
                        iconRole = SettingsRowId.PictureInPicture.iconRole(),
                        title = stringResource(Res.string.settings_picture_in_picture),
                        value = settingsBooleanValue(state.pictureInPictureEnabled),
                        trailing =
                            SettingsRowTrailing.Switch(
                                checked = state.pictureInPictureEnabled,
                                onCheckedChange = onSetPictureInPictureEnabled,
                            ),
                    )
                }
            }
        }

    SettingsSectionCard(
        title = stringResource(Res.string.settings_section_advanced_playback),
        rows = rows,
        header = header,
        footer = footer,
    )
    PlayerDevicePickers(
        settings = state.playerDeviceSettings,
        policy = policy,
        openRow = openRow,
        onSetAudioMode = onSetPlayerAudioMode,
        onSetHdrMode = onSetPlayerHdrMode,
        onSetMaxVideoResolution = onSetMaxVideoResolution,
        onSetIosPlaybackCompatibilityMode = onSetIosPlaybackCompatibilityMode,
        onDismiss = onDismiss,
    )
}

@Composable
private fun LoadingPlaybackPreferences() {
    Text(
        text = stringResource(Res.string.settings_playback_loading),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PlaybackPreferencesError(onReload: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.settings_playback_error),
            modifier = Modifier.weight(1f),
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.material3.TextButton(onClick = onReload) {
            Text(stringResource(Res.string.settings_playback_retry))
        }
    }
}

@Composable
private fun qualitySettingOption(policy: PlaybackQualityPolicy): QualityOption =
    playbackQualitySettingChoices(policy).first { option -> qualityPolicy(option) == policy.normalized() }

private fun qualityPolicy(option: QualityOption): PlaybackQualityPolicy =
    when (option.mode) {
        PlaybackQualityMode.Auto -> PlaybackQualityPolicy.Auto
        PlaybackQualityMode.Original -> PlaybackQualityPolicy.Original
        PlaybackQualityMode.Fixed -> option.maxBitrateBps?.let(PlaybackQualityPolicy::fixed) ?: PlaybackQualityPolicy.Auto
    }

@Composable
private fun qualityLabel(option: QualityOption): String {
    val bitrate = option.maxBitrateBps
    if (option.mode == PlaybackQualityMode.Original) return stringResource(Res.string.player_quality_original)
    val resolution = option.displayResolution
    return when {
        bitrate == null -> stringResource(Res.string.settings_playback_bitrate_auto)
        option.isCustom ->
            stringResource(
                Res.string.settings_playback_bitrate_custom,
                formatBitrateMbps(bitrate),
            )
        resolution != null ->
            stringResource(
                Res.string.settings_playback_bitrate_resolution,
                formatBitrateMbps(bitrate),
                resolution,
            )
        else -> stringResource(Res.string.settings_playback_bitrate_mbps, formatBitrateMbps(bitrate))
    }
}

@Composable
private fun vlcBudgetLabel(bitrateBps: Long?): String =
    if (bitrateBps == null) {
        stringResource(Res.string.settings_playback_vlc_budget_disabled)
    } else {
        val rung = qualityRungForBitrate(bitrateBps)
        qualityLabel(
            QualityOption(
                maxBitrateBps = bitrateBps,
                tier = rung?.tier,
                resolutionWidth = rung?.width,
                resolutionHeight = rung?.height,
                isCustom = rung == null,
                mode = PlaybackQualityMode.Fixed,
            ),
        )
    }

@Composable
private fun autoplayDelayLabel(delaySeconds: Int): String =
    if (delaySeconds == 0) {
        stringResource(Res.string.settings_playback_autoplay_delay_immediate)
    } else {
        stringResource(Res.string.settings_playback_autoplay_delay_seconds, delaySeconds)
    }

@Composable
private fun playerBackendLabel(backend: PlayerBackend): String =
    when (backend) {
        PlayerBackend.Auto -> stringResource(Res.string.settings_player_backend_auto)
        PlayerBackend.AVPlayer -> stringResource(Res.string.settings_player_backend_avplayer)
        PlayerBackend.VlcKit -> stringResource(Res.string.settings_player_backend_vlckit)
        PlayerBackend.ExoPlayer -> stringResource(Res.string.settings_player_backend_exoplayer)
        PlayerBackend.Mpv -> stringResource(Res.string.settings_player_backend_mpv)
        PlayerBackend.LibVlc -> stringResource(Res.string.settings_player_backend_libvlc)
    }

@Composable
private fun segmentRowTitle(type: MediaSegmentType): String =
    when (type) {
        MediaSegmentType.Intro -> stringResource(Res.string.settings_segment_intros)
        MediaSegmentType.Outro -> stringResource(Res.string.settings_segment_credits)
        MediaSegmentType.Recap -> stringResource(Res.string.settings_segment_recaps)
        MediaSegmentType.Preview -> stringResource(Res.string.settings_segment_previews)
        MediaSegmentType.Commercial -> stringResource(Res.string.settings_segment_commercials)
        MediaSegmentType.Unknown -> stringResource(Res.string.settings_segment_commercials)
    }

private fun segmentRowId(type: MediaSegmentType): SettingsRowId =
    when (type) {
        MediaSegmentType.Intro -> SettingsRowId.Intros
        MediaSegmentType.Outro -> SettingsRowId.Credits
        MediaSegmentType.Recap -> SettingsRowId.Recaps
        MediaSegmentType.Preview -> SettingsRowId.Previews
        MediaSegmentType.Commercial -> SettingsRowId.Commercials
        MediaSegmentType.Unknown -> SettingsRowId.Commercials
    }
