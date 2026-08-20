// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.jellyscope.tv.ui.LocalTvFocusZoomEnabled
import com.jellyscope.tv.ui.TvSafeAreaContent
import com.jellyscope.tv.ui.focus.LocalTvFocusCoordinator
import com.jellyscope.tv.ui.focus.rememberTvFocusCoordinator
import com.jellyscope.ui.screen.detail.DetailInteractionMode
import com.jellyscope.ui.screen.detail.LocalDetailFocusZoomEnabled
import com.jellyscope.ui.screen.detail.LocalDetailInteractionMode
import com.jellyscope.ui.theme.AppColorTheme
import com.jellyscope.ui.theme.JellyScopeTheme
import com.jellyscope.ui.theme.LocalAppBackgroundBrush

@Composable
internal fun TvPreviewSurface(
    modifier: Modifier = Modifier,
    safeArea: Boolean = false,
    theme: AppColorTheme = AppColorTheme.Ocean,
    focusZoomEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    JellyScopeTheme(theme = theme) {
        CompositionLocalProvider(
            LocalTvFocusZoomEnabled provides focusZoomEnabled,
            LocalDetailFocusZoomEnabled provides focusZoomEnabled,
            LocalDetailInteractionMode provides DetailInteractionMode.Dpad,
            // Grid-based screens (Discover/Library/Grid) require a focus coordinator;
            // provide one so their previews render instead of throwing.
            LocalTvFocusCoordinator provides rememberTvFocusCoordinator(),
        ) {
            if (safeArea) {
                TvSafeAreaContent {
                    content()
                }
            } else {
                Box(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .background(LocalAppBackgroundBrush.current),
                ) {
                    content()
                }
            }
        }
    }
}
