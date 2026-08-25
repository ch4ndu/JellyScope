// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.QualityTier
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.formatBitrateMbps
import com.jellyscope.core.domain.playback.qualityRungForBitrate
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.player.PlayerPicker
import com.jellyscope.ui.screen.player.PlayerResizeMode

@Composable
internal fun pickerTitle(picker: PlayerPicker): String =
    when (picker) {
        PlayerPicker.Subtitles -> stringResource(R.string.tv_subtitles)
        PlayerPicker.Audio -> stringResource(R.string.tv_audio)
        PlayerPicker.Backend -> stringResource(R.string.tv_player_backend)
        PlayerPicker.Quality -> stringResource(R.string.tv_quality)
        PlayerPicker.Chapters -> stringResource(R.string.tv_chapters)
        PlayerPicker.AudioOffset ->
            "${stringResource(R.string.tv_audio)} · ${stringResource(R.string.tv_timing_offset_action)}"
        PlayerPicker.SubtitleOffset ->
            "${stringResource(R.string.tv_subtitles)} · ${stringResource(R.string.tv_timing_offset_action)}"
        PlayerPicker.None,
        PlayerPicker.Speed,
        PlayerPicker.SubtitleStyle,
        PlayerPicker.Queue,
        PlayerPicker.Resize,
        -> ""
    }

@Composable
internal fun playerBackendLabel(backend: PlayerBackend): String =
    stringResource(
        when (backend) {
            PlayerBackend.Auto -> R.string.tv_player_backend_auto
            PlayerBackend.AVPlayer -> R.string.tv_player_backend_avplayer
            PlayerBackend.VlcKit -> R.string.tv_player_backend_vlckit
            PlayerBackend.ExoPlayer -> R.string.tv_settings_player_backend_exoplayer
            PlayerBackend.Mpv -> R.string.tv_settings_player_backend_mpv
            PlayerBackend.LibVlc -> R.string.tv_settings_player_backend_libvlc
        },
    )

@Composable
internal fun subtitleSecondary(option: SubtitleTrackOption): String? =
    if (option.isExternal) {
        listOfNotNull(stringResource(R.string.tv_external_subtitle), option.language).joinToString(" · ")
    } else {
        option.language
    }

@Composable
internal fun subtitleTitle(option: SubtitleTrackOption): String =
    option.displayName ?: stringResource(R.string.tv_track_subtitle_fallback, option.ordinal + 1)

@Composable
internal fun audioTitle(option: AudioTrackOption): String =
    option.displayName ?: stringResource(R.string.tv_track_audio_fallback, option.ordinal + 1)

@Composable
internal fun qualityLabel(option: QualityOption): String {
    val resolution = option.displayResolution ?: return stringResource(R.string.tv_quality_label_fallback)
    val tier =
        when (option.tier) {
            QualityTier.High -> stringResource(R.string.tv_quality_tier_high)
            QualityTier.Medium -> stringResource(R.string.tv_quality_tier_medium)
            null -> null
        }
    return if (tier == null) {
        resolution
    } else {
        stringResource(R.string.tv_quality_with_tier, resolution, tier)
    }
}

@Composable
internal fun inheritedQualityDescription(
    policy: PlaybackQualityPolicy,
    usesVlcSetting: Boolean,
): String {
    val normalized = policy.normalized()
    val effectiveLabel =
        when (normalized.mode) {
            PlaybackQualityMode.Auto -> stringResource(R.string.tv_quality_auto)
            PlaybackQualityMode.Original -> stringResource(R.string.tv_quality_original)
            PlaybackQualityMode.Fixed -> {
                val bitrate = normalized.maxBitrateBps
                val rung = qualityRungForBitrate(bitrate)
                listOfNotNull(
                    rung?.let { qualityLabel(QualityOption(bitrate, it.tier, it.width, it.height)) },
                    bitrate?.let { stringResource(R.string.tv_settings_bitrate_mbps, formatBitrateMbps(it)) },
                ).joinToString(" · ")
            }
        }
    return stringResource(
        if (usesVlcSetting) {
            R.string.tv_quality_inherited_vlc_description
        } else {
            R.string.tv_quality_inherited_default_description
        },
        effectiveLabel,
    )
}

@Composable
internal fun localMenuTitle(menu: TvPlayerLocalMenu): String =
    when (menu) {
        TvPlayerLocalMenu.Chapters -> stringResource(R.string.tv_chapters)
        TvPlayerLocalMenu.Speed -> stringResource(R.string.tv_speed)
        TvPlayerLocalMenu.SubtitleStyle -> stringResource(R.string.tv_subtitle_style)
        TvPlayerLocalMenu.Resize -> stringResource(R.string.tv_resize_mode)
        TvPlayerLocalMenu.None -> ""
    }

@Composable
internal fun PlayerResizeMode.label(): String =
    when (this) {
        PlayerResizeMode.Fit -> stringResource(R.string.tv_resize_fit)
        PlayerResizeMode.Fill -> stringResource(R.string.tv_resize_fill)
        PlayerResizeMode.Zoom -> stringResource(R.string.tv_resize_zoom)
    }
