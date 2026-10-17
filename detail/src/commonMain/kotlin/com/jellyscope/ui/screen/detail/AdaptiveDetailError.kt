// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.FocusableBox
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.tv_details_error
import com.jellyscope.ui.generated.resources.tv_retry
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AdaptiveDetailError(
    retryable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalJellyfinPalette.current.gradientBottom)
                .padding(
                    horizontal = detailHorizontalInset(),
                    vertical = DetailDimens.overscanVertical,
                ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DetailText(
            text = stringResource(Res.string.tv_details_error),
            color = LocalJellyfinPalette.current.error,
        )
        if (retryable) {
            RetryButton(
                text = stringResource(Res.string.tv_retry),
                onClick = onRetry,
                modifier = Modifier.width(DetailDimens.detailBackButtonWidth),
            )
        }
    }
}

@Composable
private fun RetryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier,
        contentDescription = text,
        focusedScale = 1.05f,
        backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
        focusedBackgroundColor = LocalJellyfinPalette.current.cyan,
        contentPadding =
            PaddingValues(
                horizontal = DetailDimens.buttonHorizontalPadding,
                vertical = DetailDimens.buttonVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
        shape = RoundedCornerShape(percent = 50),
    ) { focused ->
        DetailText(
            text = text,
            style = DetailBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color =
                if (focused) {
                    LocalJellyfinPalette.current.onFocusedLight
                } else {
                    LocalJellyfinPalette.current.textPrimary
                },
            maxLines = 1,
        )
    }
}
