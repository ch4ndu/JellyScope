// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_row_content_description
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

/** The trailing control determines whether the row is interactive. */
internal sealed interface SettingsRowTrailing {
    data object Chevron : SettingsRowTrailing

    data class Switch(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : SettingsRowTrailing

    data class Progress(
        val running: Boolean,
    ) : SettingsRowTrailing

    data object None : SettingsRowTrailing
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    iconRole: SettingsRowIconRole,
    title: String,
    trailing: SettingsRowTrailing,
    value: String? = null,
    effectiveValue: String? = null,
    description: String? = null,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalJellyfinPalette.current
    // Device policy overrides the requested value.
    val displayedValue = effectiveValue ?: value
    val visibleValue = displayedValue.takeUnless { trailing is SettingsRowTrailing.Switch }
    val destructive = iconRole == SettingsRowIconRole.Destructive
    val iconColor =
        when (iconRole) {
            SettingsRowIconRole.Primary -> palette.cyan
            SettingsRowIconRole.Accent, SettingsRowIconRole.Destructive -> palette.accentCoral
        }
    val titleColor = if (destructive) palette.accentCoral else MaterialTheme.colorScheme.onSurface
    val running = trailing is SettingsRowTrailing.Progress && trailing.running
    val interactive = enabled && !running && trailing != SettingsRowTrailing.None
    val titleAndValueDescription =
        if (displayedValue.isNullOrBlank()) {
            title
        } else {
            stringResource(Res.string.settings_row_content_description, title, displayedValue)
        }
    val rowContentDescription =
        if (description.isNullOrBlank()) {
            titleAndValueDescription
        } else {
            stringResource(Res.string.settings_row_content_description, titleAndValueDescription, description)
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = Dimensions.settingsRowMinHeight)
                .then(
                    if (interactive) {
                        when (trailing) {
                            // Keep one toggle target and accessibility node.
                            is SettingsRowTrailing.Switch ->
                                Modifier.clickable { trailing.onCheckedChange(!trailing.checked) }
                            else -> Modifier.clickable(onClick = onClick)
                        }
                    } else {
                        Modifier
                    },
                )
                // Announce the row and value once.
                .semantics(mergeDescendants = true) { contentDescription = rowContentDescription }
                .padding(horizontal = Dimensions.formSpacing, vertical = Dimensions.contentSpacing)
                .alpha(if (enabled) 1f else Dimensions.settingsRowDisabledAlpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(Dimensions.settingsRowIconSize),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimensions.settingsRowTextGap),
        ) {
            Text(
                text = title,
                color = titleColor,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!visibleValue.isNullOrBlank()) {
                Text(
                    text = visibleValue,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        when (trailing) {
            SettingsRowTrailing.Chevron ->
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (destructive) palette.accentCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.controlButtonIconSize),
                )
            is SettingsRowTrailing.Switch ->
                Switch(
                    checked = trailing.checked,
                    // The row owns the click target.
                    onCheckedChange = null,
                    enabled = enabled,
                    // Keep the unchecked thumb visible on dark palettes.
                    colors =
                        SwitchDefaults.colors(
                            uncheckedThumbColor = palette.textPrimary,
                            uncheckedBorderColor = palette.outline,
                        ),
                )
            is SettingsRowTrailing.Progress ->
                if (trailing.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimensions.controlButtonIconSize),
                        strokeWidth = Dimensions.settingsRowProgressStroke,
                    )
                }
            SettingsRowTrailing.None -> Unit
        }
    }
}
