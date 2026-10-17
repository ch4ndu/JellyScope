// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.jellyscope.ui.theme.Dimensions

@Composable
fun HeroDetailBackButton(
    contentDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnBack by rememberUpdatedState(onBack)
    TooltipIconButton(
        label = contentDescription,
        onClick = { currentOnBack() },
        modifier =
            modifier
                .zIndex(1f)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ).padding(start = Dimensions.inlineSpacing)
                .size(Dimensions.minTouchTarget),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun heroDetailTopPadding(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
fun heroDetailMessageTopPadding(): Dp =
    heroDetailTopPadding() +
        Dimensions.minTouchTarget +
        Dimensions.screenPadding
