// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.component.SafeAreaContent
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.platform.PlatformCapabilities
import com.jellyscope.ui.theme.AppColorTheme
import com.jellyscope.ui.theme.JellyScopeTheme

@Composable
internal fun JellyScopePreviewSurface(
    modifier: Modifier = Modifier,
    theme: AppColorTheme = AppColorTheme.Ocean,
    safeArea: Boolean = true,
    windowWidthTier: WindowWidthTier? = null,
    platformCapabilities: PlatformCapabilities? = null,
    content: @Composable () -> Unit,
) {
    JellyScopeTheme(theme = theme) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val widthTier = windowWidthTier ?: WindowWidthTier.fromAvailableWidth(maxWidth)
            val capabilities =
                platformCapabilities
                    ?: if (widthTier == WindowWidthTier.XLarge) {
                        PlatformCapabilities.Desktop
                    } else {
                        PlatformCapabilities.Mobile
                    }

            CompositionLocalProvider(
                LocalWindowWidthTier provides widthTier,
                LocalPlatformCapabilities provides capabilities,
            ) {
                if (safeArea) {
                    SafeAreaContent(content = content)
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }
    }
}
