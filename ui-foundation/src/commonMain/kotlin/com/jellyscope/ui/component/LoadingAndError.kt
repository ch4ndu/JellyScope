// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_retry
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(Dimensions.progressIndicatorSize),
        strokeWidth = Dimensions.progressIndicatorStroke,
    )
}

@Composable
fun RetryableError(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = textStyle,
        )
        if (retryable) {
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
            ) {
                Text(stringResource(Res.string.detail_retry))
            }
        }
    }
}
