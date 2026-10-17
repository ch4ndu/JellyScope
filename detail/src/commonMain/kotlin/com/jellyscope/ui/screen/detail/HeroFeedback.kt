// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.HeroDetailBackButton
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.heroDetailMessageTopPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import com.jellyscope.ui.generated.resources.detail_error
import com.jellyscope.ui.generated.resources.detail_retry
import com.jellyscope.ui.generated.resources.series_error
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DetailLoading(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HeroFeedback(
        onBack = onBack,
        modifier = modifier,
    ) { contentModifier ->
        Box(
            modifier = contentModifier,
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimensions.progressIndicatorSize),
                strokeWidth = Dimensions.progressIndicatorStroke,
            )
        }
    }
}

@Composable
internal fun SeriesLoading(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HeroFeedback(
        onBack = onBack,
        modifier = modifier,
    ) { contentModifier ->
        Box(
            modifier = contentModifier,
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimensions.progressIndicatorSize),
                strokeWidth = Dimensions.progressIndicatorStroke,
            )
        }
    }
}

@Composable
internal fun DetailError(
    retryable: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HeroError(
        title = stringResource(Res.string.detail_error),
        retryable = retryable,
        retryStyle = HeroRetryStyle.Outlined,
        onBack = onBack,
        onRetry = onRetry,
        modifier = modifier,
    )
}

@Composable
internal fun SeriesError(
    retryable: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HeroError(
        title = stringResource(Res.string.series_error),
        retryable = retryable,
        retryStyle = HeroRetryStyle.Filled,
        onBack = onBack,
        onRetry = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun HeroError(
    title: String,
    retryable: Boolean,
    retryStyle: HeroRetryStyle,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    HeroFeedback(
        onBack = onBack,
        modifier = modifier,
    ) { contentModifier ->
        Column(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (retryable) {
                when (retryStyle) {
                    HeroRetryStyle.Outlined ->
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                        ) {
                            Text(stringResource(Res.string.detail_retry))
                        }
                    HeroRetryStyle.Filled ->
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                        ) {
                            Text(stringResource(Res.string.detail_retry))
                        }
                }
            }
        }
    }
}

@Composable
private fun HeroFeedback(
    onBack: () -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    Box(modifier = modifier.fillMaxSize()) {
        content(
            Modifier
                .fillMaxSize()
                .padding(
                    start = horizontalContentPadding.start,
                    top = heroDetailMessageTopPadding(),
                    end = horizontalContentPadding.end,
                    bottom = appNavigationBarContentPadding(),
                ),
        )
        HeroDetailBackButton(
            contentDescription = stringResource(Res.string.detail_back),
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

private enum class HeroRetryStyle {
    Outlined,
    Filled,
}
