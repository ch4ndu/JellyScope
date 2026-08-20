// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.runtime.Composable
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerSettingDisabledReason
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.playbackQualityChoices
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_player_audio_auto
import com.jellyscope.ui.generated.resources.settings_player_audio_passthrough
import com.jellyscope.ui.generated.resources.settings_player_audio_stereo
import com.jellyscope.ui.generated.resources.settings_player_detected_unknown
import com.jellyscope.ui.generated.resources.settings_player_hdr_auto
import com.jellyscope.ui.generated.resources.settings_player_hdr_sdr
import com.jellyscope.ui.generated.resources.settings_player_option_unsupported_audio
import com.jellyscope.ui.generated.resources.settings_player_option_unsupported_hdr
import com.jellyscope.ui.generated.resources.settings_segment_commercial
import com.jellyscope.ui.generated.resources.settings_segment_intro
import com.jellyscope.ui.generated.resources.settings_segment_outro
import com.jellyscope.ui.generated.resources.settings_segment_policy_ask
import com.jellyscope.ui.generated.resources.settings_segment_policy_auto
import com.jellyscope.ui.generated.resources.settings_segment_policy_ignore
import com.jellyscope.ui.generated.resources.settings_segment_preview
import com.jellyscope.ui.generated.resources.settings_segment_recap
import org.jetbrains.compose.resources.stringResource

internal fun playbackQualitySettingChoices(selectedPolicy: PlaybackQualityPolicy): List<QualityOption> =
    playbackQualityChoices(selectedPolicy).map { choice ->
        QualityOption(
            maxBitrateBps = choice.maxBitrateBps,
            tier = null,
            resolutionWidth = choice.resolutionWidth,
            resolutionHeight = choice.resolutionHeight,
            isCustom = choice.isCustom,
            mode = choice.mode,
        )
    }

@Composable
internal fun segmentTypeLabel(type: MediaSegmentType): String =
    when (type) {
        MediaSegmentType.Intro -> stringResource(Res.string.settings_segment_intro)
        MediaSegmentType.Outro -> stringResource(Res.string.settings_segment_outro)
        MediaSegmentType.Recap -> stringResource(Res.string.settings_segment_recap)
        MediaSegmentType.Preview -> stringResource(Res.string.settings_segment_preview)
        MediaSegmentType.Commercial -> stringResource(Res.string.settings_segment_commercial)
        // Unknown is excluded from settings.
        MediaSegmentType.Unknown -> stringResource(Res.string.settings_segment_commercial)
    }

@Composable
internal fun segmentSkipPolicyLabel(policy: SegmentSkipPolicy): String =
    when (policy) {
        SegmentSkipPolicy.AutoSkip -> stringResource(Res.string.settings_segment_policy_auto)
        SegmentSkipPolicy.Ask -> stringResource(Res.string.settings_segment_policy_ask)
        SegmentSkipPolicy.Ignore -> stringResource(Res.string.settings_segment_policy_ignore)
    }

@Composable
internal fun playerAudioModeLabel(mode: PlayerAudioMode): String =
    when (mode) {
        PlayerAudioMode.Auto -> stringResource(Res.string.settings_player_audio_auto)
        PlayerAudioMode.StereoPcm -> stringResource(Res.string.settings_player_audio_stereo)
        PlayerAudioMode.PassthroughWhenSupported -> stringResource(Res.string.settings_player_audio_passthrough)
    }

@Composable
internal fun playerHdrModeLabel(mode: PlayerHdrMode): String =
    when (mode) {
        PlayerHdrMode.Auto -> stringResource(Res.string.settings_player_hdr_auto)
        PlayerHdrMode.PreferSdr -> stringResource(Res.string.settings_player_hdr_sdr)
    }

@Composable
internal fun playerSettingDisabledReasonLabel(reason: PlayerSettingDisabledReason): String =
    when (reason) {
        PlayerSettingDisabledReason.AudioRouteUnsupported ->
            stringResource(Res.string.settings_player_option_unsupported_audio)
        PlayerSettingDisabledReason.HdrDisplayUnsupported ->
            stringResource(Res.string.settings_player_option_unsupported_hdr)
    }

@Composable
internal fun String?.orUnknown(): String =
    this?.takeIf { value -> value.isNotBlank() }
        ?: stringResource(Res.string.settings_player_detected_unknown)

val autoplayDelayOptions = listOf(0, 5, 10, 15, 30, 60)
