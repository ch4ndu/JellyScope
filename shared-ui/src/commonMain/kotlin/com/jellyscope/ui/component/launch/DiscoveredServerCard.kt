// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.jellyscope.ui.component.chromeFocusGlow
import com.jellyscope.ui.theme.AmbientLaunchDimens
import com.jellyscope.ui.theme.AmbientLaunchTokens

/**
 * Fixed-palette, tap-to-connect card for a discovered server.
 *
 * The caller supplies the server values and accessibility description because
 * discovery owns that localized context.
 */
@Composable
fun DiscoveredServerCard(
    name: String,
    address: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentDescription: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val highlighted = selected || focused
    val shape = RoundedCornerShape(AmbientLaunchDimens.controlRadius)

    Row(
        modifier =
            modifier
                .widthIn(
                    min = AmbientLaunchDimens.discoveredServerCardMinWidth,
                    max = AmbientLaunchDimens.discoveredServerCardMaxWidth,
                ).chromeFocusGlow(
                    color = if (highlighted) AmbientLaunchTokens.focusGlowSoft else null,
                    elevation = AmbientLaunchDimens.focusGlowElevation,
                    shape = shape,
                ).clip(shape)
                .background(AmbientLaunchTokens.cardFill)
                .border(
                    AmbientLaunchDimens.controlBorder,
                    if (highlighted) AmbientLaunchTokens.accent else AmbientLaunchTokens.glassBorder,
                    shape,
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).then(
                    if (contentDescription == null) {
                        Modifier
                    } else {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    },
                ).padding(AmbientLaunchDimens.cardPadding),
        horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Wifi,
            contentDescription = null,
            modifier = Modifier.size(AmbientLaunchDimens.cardIconSize),
            tint = AmbientLaunchTokens.accent,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
        ) {
            Text(
                text = name,
                color = AmbientLaunchTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = address,
                color = AmbientLaunchTokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(AmbientLaunchDimens.cardChevronSize),
            tint = AmbientLaunchTokens.textSecondary,
        )
    }
}
