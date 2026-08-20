// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette

private val DetailActionButtonShape = RoundedCornerShape(percent = 50)

@Composable
fun PrimaryPlayButton(
    label: String,
    timeLeftText: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String =
        if (timeLeftText == null) {
            label
        } else {
            "$label $timeLeftText"
        },
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .heightIn(min = Dimensions.minTouchTarget)
                .semantics { this.contentDescription = contentDescription },
        shape = DetailActionButtonShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        contentPadding = detailActionButtonContentPadding(),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(Dimensions.detailActionButtonIconSize),
        )
        Spacer(Modifier.width(Dimensions.inlineSpacing))
        Text(label)
    }
}

@Composable
fun SecondaryActionButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val buttonSizeModifier =
        if (showLabel) {
            modifier.heightIn(min = Dimensions.minTouchTarget)
        } else {
            modifier.size(Dimensions.minTouchTarget)
        }
    val buttonModifier =
        buttonSizeModifier.semantics { this.contentDescription = contentDescription }
    val contentPadding =
        if (showLabel) {
            detailActionButtonContentPadding()
        } else {
            PaddingValues(Dimensions.zero)
        }
    val palette = LocalJellyfinPalette.current

    if (active) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = DetailActionButtonShape,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = palette.accentCoral,
                ),
            contentPadding = contentPadding,
        ) {
            SecondaryActionButtonContent(
                label = label,
                icon = icon,
                showLabel = showLabel,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = DetailActionButtonShape,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            contentPadding = contentPadding,
        ) {
            SecondaryActionButtonContent(
                label = label,
                icon = icon,
                showLabel = showLabel,
            )
        }
    }
}

@Composable
private fun SecondaryActionButtonContent(
    label: String,
    icon: ImageVector,
    showLabel: Boolean,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(Dimensions.detailActionButtonIconSize),
    )
    if (showLabel) {
        Spacer(Modifier.width(Dimensions.inlineSpacing))
        Text(label)
    }
}

@Composable
internal fun SelectableActionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String = label,
    modifier: Modifier = Modifier,
) {
    val buttonModifier =
        modifier
            .heightIn(min = Dimensions.minTouchTarget)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            shape = DetailActionButtonShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            contentPadding = detailActionButtonContentPadding(),
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            shape = DetailActionButtonShape,
            contentPadding = detailActionButtonContentPadding(),
        ) {
            Text(label)
        }
    }
}

private fun detailActionButtonContentPadding(): PaddingValues =
    PaddingValues(
        horizontal = Dimensions.detailActionButtonHorizontalPadding,
        vertical = Dimensions.detailActionButtonVerticalPadding,
    )
