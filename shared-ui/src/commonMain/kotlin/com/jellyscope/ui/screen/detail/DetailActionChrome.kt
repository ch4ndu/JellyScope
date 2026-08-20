// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.FocusableBox
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_updating
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PrimaryDetailPlayButton(
    label: String,
    timeLeftText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.PlayArrow,
) {
    val dpad = isDetailDpadMode()
    FocusableBox(
        onClick = onClick,
        modifier = modifier.detailActionButtonHeight(dpad),
        enabled = enabled,
        contentDescription = listOfNotNull(label, timeLeftText).joinToString(", "),
        focusedScale = 1f,
        backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
        focusedBackgroundColor = Color.White,
        focusedBorderColor = Color.White,
        contentPadding =
            PaddingValues(
                horizontal = DetailDimens.buttonHorizontalPadding,
                vertical = DetailDimens.buttonVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
        shape = RoundedCornerShape(percent = 50),
    ) { focused ->
        val foreground =
            if (focused) {
                LocalJellyfinPalette.current.onFocusedLight
            } else {
                LocalJellyfinPalette.current.textPrimary
            }
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(DetailDimens.detailActionIconSize),
            )
            // On TV, collapse to just the play/resume icon until focused, then
            // expand to the label (+ resume time), matching the secondary
            // action buttons. On mobile it stays expanded. (#165)
            AnimatedVisibility(
                visible = !dpad || focused,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(start = DetailDimens.detailIconLabelGap),
                ) {
                    DetailText(
                        text = label,
                        style = DetailActionLabelStyle,
                        color = foreground,
                        maxLines = 1,
                    )
                    timeLeftText?.let { timeLeft ->
                        DetailText(
                            text = timeLeft,
                            style = DetailActionSublabelStyle,
                            color =
                                if (focused) {
                                    LocalJellyfinPalette.current.onFocusedLight
                                } else {
                                    LocalJellyfinPalette.current.textSecondary
                                },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExpandingActionButton(
    label: String,
    icon: DetailActionIcon,
    active: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = label,
) {
    val dpad = isDetailDpadMode()
    val updatingStateDescription = stringResource(Res.string.detail_updating)
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val foreground =
        if (dpad && focused) {
            LocalJellyfinPalette.current.onFocusedLight
        } else {
            LocalJellyfinPalette.current.textPrimary
        }
    val iconColor = if (active) LocalJellyfinPalette.current.accentCoral else foreground

    androidx.compose.foundation.layout.Row(
        modifier =
            modifier
                .detailActionButtonHeight(dpad)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (dpad && focused) Color.White else LocalJellyfinPalette.current.surfaceRaised)
                .border(
                    width = if (dpad && focused) DetailDimens.focusBorder else DetailDimens.detailActionOutlineWidth,
                    color = if (dpad && focused) Color.White else LocalJellyfinPalette.current.outline,
                    shape = RoundedCornerShape(percent = 50),
                ).detailOnFocusChanged { state -> focused = state.isFocused }
                // Keep the button ALWAYS focusable/clickable-as-a-node and gate the
                // click internally. The favorite/watched toggles flip enabled=false
                // for the brief in-flight window; if that toggled the clickable's
                // own focusability the focused button dropped focus and the row's
                // focusRestorer fell back to Play. Staying enabled=true keeps focus
                // put; the action itself is ignored while disabled.
                .clickable(
                    interactionSource = interactionSource,
                    indication = if (dpad) null else LocalIndication.current,
                    enabled = true,
                    role = Role.Button,
                    onClick = { if (enabled) onClick() },
                ).detailFocusable()
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    if (!enabled) {
                        disabled()
                        stateDescription = updatingStateDescription
                    }
                }.padding(
                    horizontal = DetailDimens.buttonHorizontalPadding,
                    vertical = DetailDimens.buttonVerticalPadding,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailIcon(icon = icon, color = iconColor)
        AnimatedVisibility(
            visible = !dpad || focused,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut(),
        ) {
            DetailText(
                text = label,
                style = DetailActionLabelStyle,
                color = foreground,
                maxLines = 1,
                modifier = Modifier.padding(start = DetailDimens.detailIconLabelGap),
            )
        }
    }
}

@Composable
internal fun TrackPickerActionButton(
    label: String,
    selectedLabel: String,
    icon: DetailActionIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = listOf(label, selectedLabel).joinToString(", "),
) {
    val dpad = isDetailDpadMode()
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val foreground =
        if (dpad && focused) {
            LocalJellyfinPalette.current.onFocusedLight
        } else {
            LocalJellyfinPalette.current.textPrimary
        }

    androidx.compose.foundation.layout.Row(
        modifier =
            modifier
                .then(
                    if (dpad && focused) {
                        Modifier.width(DetailDimens.detailTrackPickerButtonWidth)
                    } else {
                        Modifier
                    },
                ).detailActionButtonHeight(dpad)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (dpad && focused) Color.White else LocalJellyfinPalette.current.surfaceRaised)
                .border(
                    width = if (dpad && focused) DetailDimens.focusBorder else DetailDimens.detailActionOutlineWidth,
                    color = if (dpad && focused) Color.White else LocalJellyfinPalette.current.outline,
                    shape = RoundedCornerShape(percent = 50),
                ).detailOnFocusChanged { state -> focused = state.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = if (dpad) null else LocalIndication.current,
                    enabled = true,
                    role = Role.Button,
                    onClick = onClick,
                ).detailFocusable()
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.padding(
                    horizontal = DetailDimens.buttonHorizontalPadding,
                    vertical = DetailDimens.buttonVerticalPadding,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dpad && focused) {
            DetailText(
                text = selectedLabel,
                style = DetailActionLabelStyle,
                color = foreground,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            DetailIcon(
                icon = DetailActionIcon.ExpandMore,
                color = foreground,
                modifier =
                    Modifier
                        .padding(start = DetailDimens.detailMetaGap)
                        .size(DetailDimens.detailTrackPickerArrowSize),
            )
        } else {
            DetailIcon(icon = icon, color = foreground)
        }
    }
}

internal fun Modifier.detailActionButtonHeight(dpad: Boolean): Modifier =
    if (dpad) {
        height(detailActionMinimumHeight(dpad = true))
    } else {
        defaultMinSize(minHeight = detailActionMinimumHeight(dpad = false))
    }

internal fun detailActionMinimumHeight(dpad: Boolean): Dp =
    if (dpad) {
        DetailDimens.detailActionHeight
    } else {
        Dimensions.minTouchTarget
    }
