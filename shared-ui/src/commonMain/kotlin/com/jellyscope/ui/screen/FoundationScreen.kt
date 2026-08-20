// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.app_name
import com.jellyscope.ui.generated.resources.foundation_plan_pointer
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
fun FoundationScreen(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(Res.string.foundation_plan_pointer),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
