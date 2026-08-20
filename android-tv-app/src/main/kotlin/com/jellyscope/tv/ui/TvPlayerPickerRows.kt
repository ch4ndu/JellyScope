// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.playbackTimingSteps
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerTimingValue
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.estimatedGbPerHour
import com.jellyscope.core.domain.playback.mbpsLabel
import com.jellyscope.tv.R
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvMenuSectionTitle(text: String) {
    TvText(
        text = text,
        modifier = Modifier.padding(top = TvDimens.playerMenuSectionTopPadding),
        style = TvSecondaryStyle.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
    )
}

@Composable
internal fun SubtitlePickerRows(
    options: List<SubtitleTrackOption>,
    localOptions: List<LocalSubtitleAsset>,
    selectedStreamIndex: Int?,
    selectedAssetId: String?,
    subtitlesOff: Boolean,
    firstRowRequester: FocusRequester,
    onSelectSubtitle: (Int?) -> Unit,
    onSelectLocalSubtitle: (String) -> Unit = {},
    timing: PlayerTimingValue,
    onOpenOffset: () -> Unit,
) {
    TvPickerRow(
        title = stringResource(R.string.tv_subtitles_none),
        secondary = null,
        selected = subtitlesOff,
        focusRequester = firstRowRequester,
        onClick = { onSelectSubtitle(null) },
    )
    if (options.isEmpty() && localOptions.isEmpty()) {
        TvPickerRow(
            title = stringResource(R.string.tv_no_subtitle_tracks),
            selected = false,
            onClick = {},
        )
    }
    options.forEach { option ->
        TvPickerRow(
            title = subtitleTitle(option),
            secondary = subtitleSecondary(option),
            selected = selectedStreamIndex == option.streamIndex,
            onClick = { onSelectSubtitle(option.streamIndex) },
        )
    }
    localOptions.forEach { option ->
        TvPickerRow(
            title = option.label,
            secondary = option.language,
            selected = selectedAssetId == option.id,
            onClick = { onSelectLocalSubtitle(option.id) },
        )
    }
    TvTimingOffsetRow(timing = timing, onOpen = onOpenOffset)
}

@Composable
internal fun AudioPickerRows(
    options: List<AudioTrackOption>,
    selectedStreamIndex: Int?,
    firstRowRequester: FocusRequester,
    onSelectAudio: (Int) -> Unit,
    timing: PlayerTimingValue,
    onOpenOffset: () -> Unit,
) {
    if (options.isEmpty()) {
        TvPickerRow(
            title = stringResource(R.string.tv_no_audio_tracks),
            selected = false,
            focusRequester = firstRowRequester,
            onClick = {},
        )
    }
    options.forEachIndexed { index, option ->
        TvPickerRow(
            title = audioTitle(option),
            secondary = option.language,
            selected = selectedStreamIndex == option.streamIndex,
            focusRequester = firstRowRequester.takeIf { index == 0 },
            onClick = { onSelectAudio(option.streamIndex) },
        )
    }
    TvTimingOffsetRow(timing = timing, onOpen = onOpenOffset)
}

/**
 * The single "Offset" entry in a track picker; its value shows only when non-zero.
 */
@Composable
private fun TvTimingOffsetRow(
    timing: PlayerTimingValue,
    onOpen: () -> Unit,
) {
    if (!timing.isSupported) return
    val offsetMs = timing.offsetMs
    TvPickerRow(
        title = stringResource(R.string.tv_timing_offset_action),
        // Rendered from the existing localized step strings rather than a
        // Kotlin-built label, so the unit stays translatable.
        secondary =
            when {
                offsetMs > 0L -> stringResource(R.string.tv_timing_step_plus, offsetMs)
                offsetMs < 0L -> stringResource(R.string.tv_timing_step_minus, -offsetMs)
                else -> null
            },
        selected = false,
        onClick = onOpen,
    )
}

/** Body of an offset panel: cumulative value first, then steps and Reset. */
@Composable
internal fun TvTimingOffsetPanelRows(
    timing: PlayerTimingValue,
    firstRowRequester: FocusRequester?,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
) {
    // Do not expose timing controls for an unavailable capability.
    if (!timing.isSupported) return
    val steps = playbackTimingSteps(timing.supportsNegative)
    TvPickerRow(
        title = stringResource(R.string.tv_timing_offset, timing.offsetMs),
        selected = false,
        focusRequester = firstRowRequester,
        onClick = {},
    )
    if (!timing.supportsNegative) {
        TvPickerRow(
            title = stringResource(R.string.tv_timing_positive_only),
            selected = false,
            onClick = {},
        )
    }
    steps.forEach { deltaMs ->
        TvPickerRow(
            title =
                if (deltaMs < 0L) {
                    stringResource(R.string.tv_timing_step_minus, -deltaMs)
                } else {
                    stringResource(R.string.tv_timing_step_plus, deltaMs)
                },
            selected = false,
            onClick = { onAdjust(deltaMs) },
        )
    }
    TvPickerRow(
        title = stringResource(R.string.tv_timing_reset),
        selected = timing.offsetMs == 0L,
        onClick = onReset,
    )
}

@Composable
internal fun QualityPickerRows(
    options: List<QualityOption>,
    selectedPolicy: PlaybackQualityPolicy,
    qualityOverrideExplicit: Boolean,
    inheritedQualityPolicy: PlaybackQualityPolicy,
    inheritedQualityUsesVlcSetting: Boolean,
    firstRowRequester: FocusRequester,
    onClearQualityOverride: () -> Unit,
    onSelectQuality: (PlaybackQualityPolicy) -> Unit,
) {
    options.forEachIndexed { index, option ->
        TvPickerRow(
            title =
                if (option.inheritsDefault) {
                    stringResource(R.string.tv_quality_use_default)
                } else {
                    when (option.mode) {
                        PlaybackQualityMode.Auto -> stringResource(R.string.tv_quality_auto)
                        PlaybackQualityMode.Original -> stringResource(R.string.tv_quality_original)
                        PlaybackQualityMode.Fixed -> stringResource(R.string.tv_quality_convert_to, qualityLabel(option))
                    }
                },
            trailing =
                option.maxBitrateBps?.takeIf { option.mode == PlaybackQualityMode.Fixed }?.let { bitrate ->
                    stringResource(
                        R.string.tv_quality_bitrate_detail,
                        bitrate.mbpsLabel(),
                        stringResource(R.string.tv_gb_per_hour, estimatedGbPerHour(bitrate)),
                    )
                },
            secondary =
                inheritedQualityDescription(
                    policy = inheritedQualityPolicy,
                    usesVlcSetting = inheritedQualityUsesVlcSetting,
                ).takeIf { option.inheritsDefault },
            selected =
                if (option.inheritsDefault) {
                    !qualityOverrideExplicit
                } else {
                    qualityOverrideExplicit &&
                        selectedPolicy.mode == option.mode &&
                        selectedPolicy.maxBitrateBps == option.maxBitrateBps
                },
            focusRequester = firstRowRequester.takeIf { index == 0 },
            onClick = {
                if (option.inheritsDefault) {
                    onClearQualityOverride()
                } else {
                    onSelectQuality(
                        when (option.mode) {
                            PlaybackQualityMode.Auto -> PlaybackQualityPolicy.Auto
                            PlaybackQualityMode.Original -> PlaybackQualityPolicy.Original
                            PlaybackQualityMode.Fixed ->
                                option.maxBitrateBps?.let(PlaybackQualityPolicy::fixed) ?: PlaybackQualityPolicy.Auto
                        },
                    )
                }
            },
        )
    }
}

@Composable
internal fun TvPickerRow(
    title: String,
    secondary: String? = null,
    trailing: String? = null,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Focus is a subtle full-width highlight strip; text colors
    // never invert.
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val foreground = LocalJellyfinPalette.current.textPrimary
    val secondaryColor = LocalJellyfinPalette.current.textSecondary
    val rowContentDescription = listOfNotNull(title, secondary, trailing).joinToString(", ")

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TvDimens.playerPickerRowHeight)
                .clip(shape)
                .background(
                    if (focused) {
                        Color.White.copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                ).then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                ).onFocusChanged { state -> focused = state.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).focusable()
                .semantics {
                    contentDescription = rowContentDescription
                    role = Role.Button
                }.padding(horizontal = TvDimens.formGap, vertical = TvDimens.playerPickerRowVerticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            TvText(
                text = title,
                color = foreground,
                maxLines = 1,
            )
            secondary?.let {
                TvText(
                    text = it,
                    color = secondaryColor,
                    style = TvSecondaryStyle,
                    maxLines = 1,
                )
            }
        }
        trailing?.let {
            TvText(
                text = it,
                modifier =
                    Modifier
                        .padding(horizontal = TvDimens.formGap)
                        .weight(1f),
                color = secondaryColor,
                style = TvSecondaryStyle,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                imageVector = TvIcons.CheckBold,
                contentDescription = null,
                tint = LocalJellyfinPalette.current.cyan,
                modifier = Modifier.size(TvDimens.playerPickerCheckSize),
            )
        }
    }
}
