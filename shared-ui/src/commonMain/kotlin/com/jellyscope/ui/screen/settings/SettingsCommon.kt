// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_feature_badge
import com.jellyscope.ui.generated.resources.settings_value_off
import com.jellyscope.ui.generated.resources.settings_value_on
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlannedFeatureRow(label: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.formSpacing, vertical = Dimensions.contentSpacing),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = stringResource(Res.string.settings_feature_badge),
                modifier =
                    Modifier.padding(
                        horizontal = Dimensions.badgeHorizontalPadding,
                        vertical = Dimensions.badgeVerticalPadding,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
internal fun settingsBooleanValue(value: Boolean): String =
    stringResource(if (value) Res.string.settings_value_on else Res.string.settings_value_off)
