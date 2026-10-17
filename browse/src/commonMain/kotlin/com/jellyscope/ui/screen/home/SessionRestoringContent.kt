// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.session_restoring
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
fun SessionRestoringContent(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.session_restoring),
        modifier = modifier.padding(Dimensions.screenPadding),
        style = MaterialTheme.typography.bodyLarge,
    )
}
