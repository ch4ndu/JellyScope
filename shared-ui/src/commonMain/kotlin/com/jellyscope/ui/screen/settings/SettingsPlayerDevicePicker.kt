// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.runtime.Composable
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_audio_output
import com.jellyscope.ui.generated.resources.settings_hdr_handling
import com.jellyscope.ui.generated.resources.settings_ios_playback_compatibility
import com.jellyscope.ui.generated.resources.settings_max_video_resolution
import com.jellyscope.ui.generated.resources.settings_picker_audio_output_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_hdr_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_ios_playback_compatibility_subtitle
import com.jellyscope.ui.generated.resources.settings_picker_max_video_resolution_subtitle
import com.jellyscope.ui.generated.resources.settings_playback_loading
import com.jellyscope.ui.generated.resources.settings_player_effective_value
import com.jellyscope.ui.generated.resources.settings_player_ios_compatibility_standard
import com.jellyscope.ui.generated.resources.settings_player_ios_compatibility_unrestricted
import com.jellyscope.ui.generated.resources.settings_player_resolution_1080
import com.jellyscope.ui.generated.resources.settings_player_resolution_1440
import com.jellyscope.ui.generated.resources.settings_player_resolution_2160
import com.jellyscope.ui.generated.resources.settings_player_resolution_360
import com.jellyscope.ui.generated.resources.settings_player_resolution_4320
import com.jellyscope.ui.generated.resources.settings_player_resolution_480
import com.jellyscope.ui.generated.resources.settings_player_resolution_720
import com.jellyscope.ui.generated.resources.settings_player_resolution_unlimited
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerAudioOutputRow(
    settings: PlayerDeviceSettings,
    policy: EffectivePlayerDevicePolicy?,
    onOpen: () -> Unit,
) {
    val selectedLabel = playerAudioModeLabel(settings.audioMode)
    val effectiveValue =
        policy
            ?.effectiveAudioMode
            ?.takeIf { mode -> mode != settings.audioMode }
            ?.let { mode -> stringResource(Res.string.settings_player_effective_value, playerAudioModeLabel(mode)) }
    SettingsRow(
        icon = SettingsRowId.AudioOutput.icon(),
        iconRole = SettingsRowId.AudioOutput.iconRole(),
        title = stringResource(Res.string.settings_audio_output),
        value =
            if (policy == null) {
                stringResource(Res.string.settings_playback_loading)
            } else {
                selectedLabel
            },
        effectiveValue = effectiveValue,
        trailing = SettingsRowTrailing.Chevron,
        onClick = onOpen,
        enabled = policy != null,
    )
}

@Composable
internal fun PlayerHdrHandlingRow(
    settings: PlayerDeviceSettings,
    policy: EffectivePlayerDevicePolicy?,
    onOpen: () -> Unit,
) {
    val selectedLabel = playerHdrModeLabel(settings.hdrMode)
    val effectiveValue =
        policy
            ?.effectiveHdrMode
            ?.takeIf { mode -> mode != settings.hdrMode }
            ?.let { mode -> stringResource(Res.string.settings_player_effective_value, playerHdrModeLabel(mode)) }
    SettingsRow(
        icon = SettingsRowId.HdrHandling.icon(),
        iconRole = SettingsRowId.HdrHandling.iconRole(),
        title = stringResource(Res.string.settings_hdr_handling),
        value =
            if (policy == null) {
                stringResource(Res.string.settings_playback_loading)
            } else {
                selectedLabel
            },
        effectiveValue = effectiveValue,
        trailing = SettingsRowTrailing.Chevron,
        onClick = onOpen,
        enabled = policy != null,
    )
}

@Composable
internal fun PlayerMaximumVideoResolutionRow(
    settings: PlayerDeviceSettings,
    onOpen: () -> Unit,
) {
    SettingsRow(
        icon = SettingsRowId.MaximumVideoResolution.icon(),
        iconRole = SettingsRowId.MaximumVideoResolution.iconRole(),
        title = stringResource(Res.string.settings_max_video_resolution),
        value = playerVideoResolutionLabel(settings.maxVideoResolution),
        trailing = SettingsRowTrailing.Chevron,
        onClick = onOpen,
    )
}

@Composable
internal fun PlaybackCompatibilityRow(
    settings: PlayerDeviceSettings,
    onOpen: () -> Unit,
) {
    SettingsRow(
        icon = SettingsRowId.IosPlaybackCompatibility.icon(),
        iconRole = SettingsRowId.IosPlaybackCompatibility.iconRole(),
        title = stringResource(Res.string.settings_ios_playback_compatibility),
        value = playbackCompatibilityLabel(settings.iosPlaybackCompatibilityMode),
        trailing = SettingsRowTrailing.Chevron,
        onClick = onOpen,
    )
}

@Composable
internal fun PlayerDevicePickers(
    settings: PlayerDeviceSettings,
    policy: EffectivePlayerDevicePolicy?,
    openRow: SettingsRowId?,
    onSetAudioMode: (PlayerAudioMode) -> Unit,
    onSetHdrMode: (PlayerHdrMode) -> Unit,
    onSetMaxVideoResolution: (PlayerVideoResolutionLimit) -> Unit,
    onSetIosPlaybackCompatibilityMode: (IosPlaybackCompatibilityMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val audioDisabledReason =
        policy?.audioDisabledReason?.let { reason -> playerSettingDisabledReasonLabel(reason) }
    val audioOptions =
        listOf(
            SettingsPickerOption(PlayerAudioMode.Auto, playerAudioModeLabel(PlayerAudioMode.Auto)),
            SettingsPickerOption(PlayerAudioMode.StereoPcm, playerAudioModeLabel(PlayerAudioMode.StereoPcm)),
            SettingsPickerOption(
                value = PlayerAudioMode.PassthroughWhenSupported,
                label = playerAudioModeLabel(PlayerAudioMode.PassthroughWhenSupported),
                disabledReason = audioDisabledReason,
            ),
        )
    val hdrDisabledReason =
        policy?.hdrDisabledReason?.let { reason -> playerSettingDisabledReasonLabel(reason) }
    val hdrOptions =
        listOf(
            SettingsPickerOption(
                value = PlayerHdrMode.Auto,
                label = playerHdrModeLabel(PlayerHdrMode.Auto),
                disabledReason = hdrDisabledReason,
            ),
            SettingsPickerOption(PlayerHdrMode.PreferSdr, playerHdrModeLabel(PlayerHdrMode.PreferSdr)),
        )
    val resolutionOptions =
        PlayerVideoResolutionLimit.entries.map { limit ->
            SettingsPickerOption(value = limit, label = playerVideoResolutionLabel(limit))
        }
    val compatibilityOptions =
        IosPlaybackCompatibilityMode.entries.map { mode ->
            SettingsPickerOption(value = mode, label = playbackCompatibilityLabel(mode))
        }

    if (openRow == SettingsRowId.AudioOutput) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_audio_output),
            subtitle = stringResource(Res.string.settings_picker_audio_output_subtitle),
            options = audioOptions,
            selectedValue = settings.audioMode,
            onOptionSelected = onSetAudioMode,
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.HdrHandling) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_hdr_handling),
            subtitle = stringResource(Res.string.settings_picker_hdr_subtitle),
            options = hdrOptions,
            selectedValue = settings.hdrMode,
            onOptionSelected = onSetHdrMode,
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.MaximumVideoResolution) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_max_video_resolution),
            subtitle = stringResource(Res.string.settings_picker_max_video_resolution_subtitle),
            options = resolutionOptions,
            selectedValue = settings.maxVideoResolution,
            onOptionSelected = onSetMaxVideoResolution,
            onDismiss = onDismiss,
        )
    }
    if (openRow == SettingsRowId.IosPlaybackCompatibility) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_ios_playback_compatibility),
            subtitle = stringResource(Res.string.settings_picker_ios_playback_compatibility_subtitle),
            options = compatibilityOptions,
            selectedValue = settings.iosPlaybackCompatibilityMode,
            onOptionSelected = onSetIosPlaybackCompatibilityMode,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun playbackCompatibilityLabel(mode: IosPlaybackCompatibilityMode): String =
    when (mode) {
        IosPlaybackCompatibilityMode.Standard ->
            stringResource(Res.string.settings_player_ios_compatibility_standard)
        IosPlaybackCompatibilityMode.Unrestricted ->
            stringResource(Res.string.settings_player_ios_compatibility_unrestricted)
    }

@Composable
internal fun playerVideoResolutionLabel(limit: PlayerVideoResolutionLimit): String =
    when (limit) {
        PlayerVideoResolutionLimit.Unlimited ->
            stringResource(Res.string.settings_player_resolution_unlimited)
        PlayerVideoResolutionLimit.Height4320 -> stringResource(Res.string.settings_player_resolution_4320)
        PlayerVideoResolutionLimit.Height2160 -> stringResource(Res.string.settings_player_resolution_2160)
        PlayerVideoResolutionLimit.Height1440 -> stringResource(Res.string.settings_player_resolution_1440)
        PlayerVideoResolutionLimit.Height1080 -> stringResource(Res.string.settings_player_resolution_1080)
        PlayerVideoResolutionLimit.Height720 -> stringResource(Res.string.settings_player_resolution_720)
        PlayerVideoResolutionLimit.Height480 -> stringResource(Res.string.settings_player_resolution_480)
        PlayerVideoResolutionLimit.Height360 -> stringResource(Res.string.settings_player_resolution_360)
    }
