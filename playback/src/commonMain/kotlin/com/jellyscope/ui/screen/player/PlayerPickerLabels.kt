// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.QualityTier
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.mbpsLabel
import com.jellyscope.core.domain.playback.qualityRungForBitrate
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_audio
import com.jellyscope.ui.generated.resources.player_backend
import com.jellyscope.ui.generated.resources.player_chapters
import com.jellyscope.ui.generated.resources.player_quality
import com.jellyscope.ui.generated.resources.player_quality_auto
import com.jellyscope.ui.generated.resources.player_quality_bitrate_detail
import com.jellyscope.ui.generated.resources.player_quality_inherited_default_description
import com.jellyscope.ui.generated.resources.player_quality_inherited_vlc_description
import com.jellyscope.ui.generated.resources.player_quality_label_fallback
import com.jellyscope.ui.generated.resources.player_quality_original
import com.jellyscope.ui.generated.resources.player_quality_tier_high
import com.jellyscope.ui.generated.resources.player_quality_tier_medium
import com.jellyscope.ui.generated.resources.player_quality_use_default
import com.jellyscope.ui.generated.resources.player_quality_with_tier
import com.jellyscope.ui.generated.resources.player_queue
import com.jellyscope.ui.generated.resources.player_resize
import com.jellyscope.ui.generated.resources.player_resize_crop
import com.jellyscope.ui.generated.resources.player_resize_fill
import com.jellyscope.ui.generated.resources.player_resize_fit
import com.jellyscope.ui.generated.resources.player_resize_zoom
import com.jellyscope.ui.generated.resources.player_speed
import com.jellyscope.ui.generated.resources.player_subtitle_style
import com.jellyscope.ui.generated.resources.player_subtitles
import com.jellyscope.ui.generated.resources.player_timing_offset_action
import com.jellyscope.ui.generated.resources.settings_player_backend_auto
import com.jellyscope.ui.generated.resources.settings_player_backend_avplayer
import com.jellyscope.ui.generated.resources.settings_player_backend_exoplayer
import com.jellyscope.ui.generated.resources.settings_player_backend_libvlc
import com.jellyscope.ui.generated.resources.settings_player_backend_mpv
import com.jellyscope.ui.generated.resources.settings_player_backend_vlckit
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun pickerTitle(picker: PlayerPicker): String =
    when (picker) {
        PlayerPicker.None -> ""
        PlayerPicker.Chapters -> stringResource(Res.string.player_chapters)
        PlayerPicker.Subtitles -> stringResource(Res.string.player_subtitles)
        PlayerPicker.Audio -> stringResource(Res.string.player_audio)
        PlayerPicker.Backend -> stringResource(Res.string.player_backend)
        PlayerPicker.Quality -> stringResource(Res.string.player_quality)
        PlayerPicker.Speed -> stringResource(Res.string.player_speed)
        PlayerPicker.SubtitleStyle -> stringResource(Res.string.player_subtitle_style)
        PlayerPicker.Resize -> stringResource(Res.string.player_resize)
        PlayerPicker.Queue -> stringResource(Res.string.player_queue)
        PlayerPicker.AudioOffset ->
            "${stringResource(Res.string.player_audio)} · ${stringResource(Res.string.player_timing_offset_action)}"
        PlayerPicker.SubtitleOffset ->
            "${stringResource(Res.string.player_subtitles)} · ${stringResource(Res.string.player_timing_offset_action)}"
    }

@Composable
internal fun playerBackendLabel(backend: PlayerBackend): String =
    stringResource(
        when (backend) {
            PlayerBackend.Auto -> Res.string.settings_player_backend_auto
            PlayerBackend.AVPlayer -> Res.string.settings_player_backend_avplayer
            PlayerBackend.VlcKit -> Res.string.settings_player_backend_vlckit
            PlayerBackend.ExoPlayer -> Res.string.settings_player_backend_exoplayer
            PlayerBackend.Mpv -> Res.string.settings_player_backend_mpv
            PlayerBackend.LibVlc -> Res.string.settings_player_backend_libvlc
        },
    )

@Composable
internal fun resizeModeLabel(
    resizeMode: PlayerResizeMode,
    desktopResizeModes: Boolean,
): String =
    if (desktopResizeModes) {
        when (resizeMode) {
            PlayerResizeMode.Fit -> stringResource(Res.string.player_resize_fit)
            PlayerResizeMode.Fill,
            PlayerResizeMode.Zoom,
            -> stringResource(Res.string.player_resize_crop)
        }
    } else {
        when (resizeMode) {
            PlayerResizeMode.Fit -> stringResource(Res.string.player_resize_fit)
            PlayerResizeMode.Fill -> stringResource(Res.string.player_resize_fill)
            PlayerResizeMode.Zoom -> stringResource(Res.string.player_resize_zoom)
        }
    }

@Composable
internal fun qualityLabel(option: QualityOption): String {
    if (option.inheritsDefault) return stringResource(Res.string.player_quality_use_default)
    if (option.mode == PlaybackQualityMode.Auto) return stringResource(Res.string.player_quality_auto)
    if (option.mode == PlaybackQualityMode.Original) return stringResource(Res.string.player_quality_original)
    val maxBitrateBps = option.maxBitrateBps ?: return stringResource(Res.string.player_quality_original)
    val tier =
        when (option.tier) {
            QualityTier.High -> stringResource(Res.string.player_quality_tier_high)
            QualityTier.Medium -> stringResource(Res.string.player_quality_tier_medium)
            null -> null
        }
    val resolution = option.displayResolution ?: stringResource(Res.string.player_quality_label_fallback)
    val quality =
        tier?.let { label ->
            stringResource(Res.string.player_quality_with_tier, resolution, label)
        } ?: resolution
    val bitrate = stringResource(Res.string.player_quality_bitrate_detail, maxBitrateBps.mbpsLabel())
    return listOf(quality, bitrate).joinToString(" · ")
}

@Composable
internal fun inheritedQualityDescription(
    policy: PlaybackQualityPolicy,
    usesVlcSetting: Boolean,
): String {
    val normalized = policy.normalized()
    val effectiveLabel =
        when (normalized.mode) {
            PlaybackQualityMode.Auto -> stringResource(Res.string.player_quality_auto)
            PlaybackQualityMode.Original -> stringResource(Res.string.player_quality_original)
            PlaybackQualityMode.Fixed -> {
                val bitrate = normalized.maxBitrateBps
                val rung = qualityRungForBitrate(bitrate)
                qualityLabel(
                    QualityOption(
                        maxBitrateBps = bitrate,
                        tier = rung?.tier,
                        resolutionWidth = rung?.width,
                        resolutionHeight = rung?.height,
                        isCustom = rung == null,
                        mode = PlaybackQualityMode.Fixed,
                    ),
                )
            }
        }
    return stringResource(
        if (usesVlcSetting) {
            Res.string.player_quality_inherited_vlc_description
        } else {
            Res.string.player_quality_inherited_default_description
        },
        effectiveLabel,
    )
}
