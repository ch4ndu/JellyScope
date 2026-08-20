// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityChoice
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.formatBitrateMbps
import com.jellyscope.core.domain.playback.playbackQualityChoices
import com.jellyscope.tv.R

@Composable
internal fun bitrateLabel(bitrate: Long?): String =
    bitrate?.let { value ->
        stringResource(R.string.tv_settings_bitrate_mbps, formatBitrateMbps(value))
    } ?: stringResource(R.string.tv_settings_bitrate_auto)

/** The selected policy is always represented, including a stored off-ladder Fixed value. */
internal fun settingsQualityChoice(policy: PlaybackQualityPolicy): PlaybackQualityChoice {
    val normalized = policy.normalized()
    return playbackQualityChoices(normalized).first { choice -> choice.toPlaybackQualityPolicy() == normalized }
}

internal fun PlaybackQualityChoice.toPlaybackQualityPolicy(): PlaybackQualityPolicy =
    when (mode) {
        PlaybackQualityMode.Auto -> PlaybackQualityPolicy.Auto
        PlaybackQualityMode.Original -> PlaybackQualityPolicy.Original
        PlaybackQualityMode.Fixed -> maxBitrateBps?.let(PlaybackQualityPolicy::fixed) ?: PlaybackQualityPolicy.Auto
    }

@Composable
internal fun settingsQualityLabel(option: PlaybackQualityChoice): String {
    val bitrate = option.maxBitrateBps
    val resolution = option.resolutionHeight?.let { height -> "${height}p" }
    return when {
        option.mode == PlaybackQualityMode.Original -> stringResource(R.string.tv_quality_original)
        bitrate == null -> stringResource(R.string.tv_settings_bitrate_auto)
        option.isCustom ->
            stringResource(
                R.string.tv_settings_bitrate_custom,
                formatBitrateMbps(bitrate),
            )
        resolution != null ->
            stringResource(
                R.string.tv_settings_bitrate_resolution,
                formatBitrateMbps(bitrate),
                resolution,
            )
        else -> bitrateLabel(bitrate)
    }
}

@Composable
internal fun vlcBudgetLabel(bitrate: Long?): String =
    bitrate?.let { value ->
        settingsQualityLabel(settingsQualityChoice(PlaybackQualityPolicy.fixed(value)))
    } ?: stringResource(R.string.tv_settings_vlc_budget_disabled)

@Composable
internal fun segmentTypeLabel(type: MediaSegmentType): String =
    when (type) {
        MediaSegmentType.Intro -> stringResource(R.string.tv_settings_segment_intro)
        MediaSegmentType.Outro -> stringResource(R.string.tv_settings_segment_outro)
        MediaSegmentType.Recap -> stringResource(R.string.tv_settings_segment_recap)
        MediaSegmentType.Preview -> stringResource(R.string.tv_settings_segment_preview)
        MediaSegmentType.Commercial -> stringResource(R.string.tv_settings_segment_commercial)
        // Unknown never reaches settings rows (segmentSkipTypes excludes it).
        MediaSegmentType.Unknown -> stringResource(R.string.tv_settings_segment_commercial)
    }

@Composable
internal fun segmentSkipPolicyLabel(policy: SegmentSkipPolicy): String =
    when (policy) {
        SegmentSkipPolicy.AutoSkip -> stringResource(R.string.tv_settings_segment_policy_auto)
        SegmentSkipPolicy.Ask -> stringResource(R.string.tv_settings_segment_policy_ask)
        SegmentSkipPolicy.Ignore -> stringResource(R.string.tv_settings_segment_policy_ignore)
    }

@Composable
internal fun tvPlayerAudioModeLabel(mode: PlayerAudioMode): String =
    when (mode) {
        PlayerAudioMode.Auto -> stringResource(R.string.tv_settings_audio_auto)
        PlayerAudioMode.StereoPcm -> stringResource(R.string.tv_settings_audio_stereo)
        PlayerAudioMode.PassthroughWhenSupported -> stringResource(R.string.tv_settings_audio_passthrough)
    }

@Composable
internal fun tvPlayerHdrModeLabel(mode: PlayerHdrMode): String =
    when (mode) {
        PlayerHdrMode.Auto -> stringResource(R.string.tv_settings_hdr_auto)
        PlayerHdrMode.PreferSdr -> stringResource(R.string.tv_settings_hdr_sdr)
    }

@Composable
internal fun String?.orTvUnknown(): String =
    this?.takeIf { value -> value.isNotBlank() }
        ?: stringResource(R.string.tv_settings_player_unknown)
