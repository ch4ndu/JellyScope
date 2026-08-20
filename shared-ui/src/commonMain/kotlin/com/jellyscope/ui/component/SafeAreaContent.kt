// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.jellyscope.ui.theme.LocalAppBackgroundBrush

/**
 * Edge-to-edge root wrapper: draws the app-wide navy background gradient
 * behind the system bars (per the OTT mockups) while the content is padded by
 * the safe-drawing insets so no screen is clipped by status bars, display
 * cutouts, or navigation bars on any platform.
 */
@Composable
fun SafeAreaContent(
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(LocalAppBackgroundBrush.current),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(contentWindowInsets),
            ) {
                content()
            }
        }
    }
}
