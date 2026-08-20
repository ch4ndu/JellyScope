// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette

@Composable
internal fun SettingsSectionCard(
    title: String,
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val palette = LocalJellyfinPalette.current
    val shape = MaterialTheme.shapes.medium

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 0.08.em,
            modifier = Modifier.padding(horizontal = Dimensions.formSpacing, vertical = Dimensions.contentSpacing),
        )
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(
                        width = Dimensions.settingsCardBorderWidth,
                        color = palette.outline.copy(alpha = Dimensions.settingsCardBorderAlpha),
                        shape = shape,
                    ),
            shape = shape,
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.surface.copy(alpha = Dimensions.settingsCardContainerAlpha),
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                header?.let { content ->
                    Column(
                        modifier =
                            Modifier.padding(
                                horizontal = Dimensions.formSpacing,
                                vertical = Dimensions.contentSpacing,
                            ),
                        content = { content() },
                    )
                }
                rows.forEachIndexed { index, row ->
                    row()
                    if (index < rows.lastIndex) {
                        HorizontalDivider(
                            thickness = Dp.Hairline,
                            color = palette.outline.copy(alpha = Dimensions.settingsCardBorderAlpha),
                        )
                    }
                }
                footer?.let { content ->
                    Column(
                        modifier =
                            Modifier.padding(
                                horizontal = Dimensions.formSpacing,
                                vertical = Dimensions.contentSpacing,
                            ),
                        content = { content() },
                    )
                }
            }
        }
    }
}
