// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.ui.theme.LocalJellyfinPalette

@Composable
internal fun TvCircleButton(
    contentDescription: String,
    focusRequester: FocusRequester,
    upRequester: FocusRequester,
    onClick: () -> Unit,
    icon: PlayerButtonIcon,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = TvDimens.playerButtonSize,
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    // Theme-standard: dark translucent circle at rest, solid white with a
    // dark glyph when focused — same as every other control.
    val background =
        if (focused) {
            Color.White
        } else {
            Color.White.copy(alpha = 0.14f)
        }
    val foreground =
        if (focused) {
            LocalJellyfinPalette.current.onFocusedLight
        } else {
            LocalJellyfinPalette.current.textPrimary
        }

    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .border(
                    width = if (focused) TvDimens.focusBorder else 0.dp,
                    color = if (focused) Color.White else Color.Transparent,
                    shape = CircleShape,
                ).focusRequester(focusRequester)
                .focusProperties { up = upRequester }
                .onFocusChanged { state -> focused = state.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).focusable()
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        PlayerIcon(icon = icon, color = foreground)
    }
}

// Read-only playback diagnostics rendered as a non-modal top overlay. It does
// not request focus; the Debug player-control button is the only toggle.

@Composable
internal fun PlayerIcon(
    icon: PlayerButtonIcon,
    color: Color,
) {
    Icon(
        imageVector =
            when (icon) {
                PlayerButtonIcon.Subtitles -> TvIcons.Subtitles
                PlayerButtonIcon.Audio -> TvIcons.MusicNote
                PlayerButtonIcon.Quality -> TvIcons.QualityHigh
                PlayerButtonIcon.Chapters -> TvIcons.FormatListBulleted
                PlayerButtonIcon.Speed -> TvIcons.Speedometer
                PlayerButtonIcon.SubtitleStyle -> TvIcons.Palette
                PlayerButtonIcon.Resize -> TvIcons.AspectRatio
                PlayerButtonIcon.Debug -> TvIcons.InformationOutline
                PlayerButtonIcon.Play -> TvIcons.Play
                PlayerButtonIcon.Pause -> TvIcons.Pause
            },
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(TvDimens.playerIconSize),
    )
}

internal enum class PlayerButtonIcon {
    Subtitles,
    Audio,
    Quality,
    Chapters,
    Speed,
    SubtitleStyle,
    Resize,
    Debug,
    Play,
    Pause,
}
