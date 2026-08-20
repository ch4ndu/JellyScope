// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jellyscope.ui.theme.AmbientLaunchDimens
import com.jellyscope.ui.theme.AmbientLaunchTokens

/** Filled fixed-cyan Ambient call-to-action with an optional inline spinner. */
@Composable
fun AmbientPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(AmbientLaunchDimens.controlRadius)

    Row(
        modifier =
            modifier
                .heightIn(min = AmbientLaunchDimens.buttonMinHeight)
                .widthIn(max = 240.dp)
                .clip(shape)
                .background(
                    AmbientLaunchTokens.accent.copy(
                        alpha = if (enabled && !loading) 1f else AmbientLaunchDimens.disabledAlpha,
                    ),
                ).clickable(
                    enabled = enabled && !loading,
                    role = Role.Button,
                    onClick = onClick,
                ).then(
                    if (contentDescription == null) {
                        Modifier
                    } else {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    },
                ).padding(
                    horizontal = AmbientLaunchDimens.buttonHorizontalPadding,
                    vertical = AmbientLaunchDimens.buttonVerticalPadding,
                ),
        horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(AmbientLaunchDimens.buttonSpinnerSize),
                color = AmbientLaunchTokens.onAccent,
                strokeWidth = AmbientLaunchDimens.buttonSpinnerStroke,
            )
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = AmbientLaunchTokens.onAccent,
            style = TextStyle(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
    }
}

/** Outlined fixed-palette secondary action for Ambient launch screens. */
@Composable
fun AmbientGhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(AmbientLaunchDimens.controlRadius)

    Box(
        modifier =
            modifier
                .heightIn(min = AmbientLaunchDimens.buttonMinHeight)
                .clip(shape)
                .border(AmbientLaunchDimens.controlBorder, AmbientLaunchTokens.glassBorder, shape)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).then(
                    if (contentDescription == null) {
                        Modifier
                    } else {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    },
                ).padding(
                    horizontal = AmbientLaunchDimens.buttonHorizontalPadding,
                    vertical = AmbientLaunchDimens.buttonVerticalPadding,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = AmbientLaunchTokens.textPrimary.copy(alpha = if (enabled) 1f else AmbientLaunchDimens.disabledAlpha),
            style = TextStyle(fontWeight = FontWeight.SemiBold),
        )
    }
}
