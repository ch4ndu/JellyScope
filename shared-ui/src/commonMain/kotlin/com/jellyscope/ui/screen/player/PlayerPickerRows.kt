// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.jellyscope.core.domain.model.playbackTimingSteps
import com.jellyscope.core.domain.playback.PlayerTimingValue
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.ui.component.ChromeDimens
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_timing_offset
import com.jellyscope.ui.generated.resources.player_timing_offset_action
import com.jellyscope.ui.generated.resources.player_timing_positive_only
import com.jellyscope.ui.generated.resources.player_timing_reset
import com.jellyscope.ui.generated.resources.player_timing_step_minus
import com.jellyscope.ui.generated.resources.player_timing_step_plus
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PickerRow(
    selected: Boolean,
    title: String,
    secondary: String? = null,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val rowContentDescription = listOfNotNull(title, secondary, trailing).joinToString(", ")
    val shape = RoundedCornerShape(ChromeDimens.cardRadius)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = ChromeDimens.playerPickerRowHeight)
                .clip(shape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = PLAYER_PICKER_SELECTED_ROW_ALPHA)
                    } else {
                        Color.Transparent
                    },
                ).clickable(
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    contentDescription = rowContentDescription
                    role = Role.Button
                }.padding(
                    horizontal = Dimensions.formSpacing,
                    vertical = ChromeDimens.detailTrackPickerRowVerticalPadding,
                ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            secondary?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Text(
                text = it,
                modifier =
                    Modifier
                        .padding(horizontal = Dimensions.inlineSpacing)
                        .weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ChromeDimens.detailTrackPickerCheckSize),
            )
        }
    }
}

@Composable
internal fun PlayerTimingOffsetRow(
    timing: PlayerTimingValue,
    onOpen: () -> Unit,
) {
    if (!timing.isSupported) return
    val offsetMs = timing.offsetMs
    PickerRow(
        selected = false,
        title = stringResource(Res.string.player_timing_offset_action),
        // Reuse localized timing units.
        secondary =
            when {
                offsetMs > 0L -> stringResource(Res.string.player_timing_step_plus, offsetMs)
                offsetMs < 0L -> stringResource(Res.string.player_timing_step_minus, -offsetMs)
                else -> null
            },
        onClick = onOpen,
    )
}

@Composable
internal fun PlayerTimingOffsetPanel(
    timing: PlayerTimingValue,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
) {
    if (!timing.isSupported) return
    val steps = playbackTimingSteps(timing.supportsNegative)
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Text(
            text = stringResource(Res.string.player_timing_offset, timing.offsetMs),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        if (!timing.supportsNegative) {
            Text(
                text = stringResource(Res.string.player_timing_positive_only),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
            steps.forEach { deltaMs ->
                Button(
                    onClick = { onAdjust(deltaMs) },
                    modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                ) {
                    Text(
                        if (deltaMs < 0L) {
                            stringResource(Res.string.player_timing_step_minus, -deltaMs)
                        } else {
                            stringResource(Res.string.player_timing_step_plus, deltaMs)
                        },
                    )
                }
            }
        }
        Button(
            onClick = onReset,
            modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
        ) {
            Text(stringResource(Res.string.player_timing_reset))
        }
    }
}

@Composable
internal fun SpeedControls(
    selectedSpeed: Float,
    onSetPlaybackSpeed: (Float) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        itemsIndexed(
            items = playbackSpeeds(),
            key = { index, speed -> "speed-$speed-$index" },
        ) { _, speed ->
            PickerRow(
                selected = selectedSpeed == speed,
                title = speed.speedLabel(),
                onClick = { onSetPlaybackSpeed(speed) },
            )
        }
    }
}

@Composable
internal fun SubtitleStyleControls(
    style: SubtitleStyle,
    onSetSubtitleStyle: (SubtitleStyle) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        itemsIndexed(
            items = subtitleStyleChoices(),
            key = { _, choice -> "subtitle-style-${choice.scale}" },
        ) { _, choice ->
            PickerRow(
                selected = style.fontScale == choice.scale,
                title = stringResource(choice.label),
                onClick = { onSetSubtitleStyle(style.copy(fontScale = choice.scale)) },
            )
        }
    }
}

@Composable
internal fun ResizePicker(
    selectedResizeMode: PlayerResizeMode,
    desktopResizeModes: Boolean,
    onSetResizeMode: (PlayerResizeMode) -> Unit,
) {
    val options =
        if (desktopResizeModes) {
            listOf(PlayerResizeMode.Fit, PlayerResizeMode.Fill)
        } else {
            listOf(PlayerResizeMode.Fit, PlayerResizeMode.Fill, PlayerResizeMode.Zoom)
        }
    LazyColumn(
        modifier = Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        itemsIndexed(
            items = options,
            key = { _, option -> "resize-$option" },
        ) { _, option ->
            PickerRow(
                selected =
                    selectedResizeMode == option ||
                        desktopResizeModes &&
                        option == PlayerResizeMode.Fill &&
                        selectedResizeMode == PlayerResizeMode.Zoom,
                title = resizeModeLabel(option, desktopResizeModes),
                onClick = { onSetResizeMode(option) },
            )
        }
    }
}
