// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.adaptive

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.jellyscope.ui.theme.Dimensions

enum class WindowWidthTier {
    Compact,
    Medium,
    Expanded,
    XLarge,
    ;

    companion object {
        /**
         * Width tiers are resolved from the actual available width measured by
         * the app shell: Compact < 600.dp, Medium 600-839.dp, Expanded
         * 840-1239.dp, and XLarge >= 1240.dp.
         */
        fun fromAvailableWidth(width: Dp): WindowWidthTier =
            when {
                width < Dimensions.windowWidthMediumMin -> Compact
                width < Dimensions.windowWidthExpandedMin -> Medium
                width < Dimensions.windowWidthXLargeMin -> Expanded
                else -> XLarge
            }
    }
}

val LocalWindowWidthTier = staticCompositionLocalOf { WindowWidthTier.Compact }
