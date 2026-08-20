// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import com.jellyscope.ui.theme.Dimensions

internal val LocalStartSafeDrawingConsumed = staticCompositionLocalOf { false }

@Immutable
data class AdaptiveHorizontalContentPadding(
    val start: Dp,
    val end: Dp,
) {
    val horizontal: Dp
        get() = start + end

    val max: Dp
        get() =
            if (start > end) {
                start
            } else {
                end
            }

    fun asPaddingValues(
        top: Dp = Dimensions.zero,
        bottom: Dp = Dimensions.zero,
    ): PaddingValues =
        PaddingValues(
            start = start,
            top = top,
            end = end,
            bottom = bottom,
        )
}

fun Modifier.expandIntoHorizontalContentPadding(contentPadding: AdaptiveHorizontalContentPadding): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        }

        val startPadding = contentPadding.start.roundToPx()
        val endPadding = contentPadding.end.roundToPx()
        val totalPadding = startPadding + endPadding
        val expandedMaxWidth = constraints.maxWidth + totalPadding
        val expandedMinWidth = (constraints.minWidth + totalPadding).coerceAtMost(expandedMaxWidth)
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = expandedMinWidth,
                    maxWidth = expandedMaxWidth,
                ),
            )
        val layoutWidth =
            (placeable.width - totalPadding)
                .coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(layoutWidth, placeable.height) {
            placeable.placeRelative(-startPadding, 0)
        }
    }

@Composable
fun adaptiveScreenPadding(): Dp =
    when (LocalWindowWidthTier.current) {
        WindowWidthTier.Compact -> Dimensions.screenPadding
        WindowWidthTier.Medium -> Dimensions.adaptiveMediumScreenPadding
        WindowWidthTier.Expanded,
        WindowWidthTier.XLarge,
        -> Dimensions.adaptiveExpandedScreenPadding
    }

@Composable
fun adaptiveHorizontalContentPadding(): AdaptiveHorizontalContentPadding {
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawingPadding =
        WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal)
            .asPaddingValues()
    val screenPadding = adaptiveScreenPadding()
    val startSafeDrawingConsumed = LocalStartSafeDrawingConsumed.current
    val startSafeDrawingPadding =
        if (startSafeDrawingConsumed) {
            Dimensions.zero
        } else {
            safeDrawingPadding.calculateStartPadding(layoutDirection)
        }

    return AdaptiveHorizontalContentPadding(
        start = screenPadding + startSafeDrawingPadding,
        end = screenPadding + safeDrawingPadding.calculateEndPadding(layoutDirection),
    )
}

@Composable
fun adaptiveHomeHeroAspectRatio(): Float =
    when (LocalWindowWidthTier.current) {
        WindowWidthTier.Compact -> Dimensions.homeHeroAspectRatio
        WindowWidthTier.Medium,
        WindowWidthTier.Expanded,
        WindowWidthTier.XLarge,
        -> Dimensions.homeHeroWideAspectRatio
    }

@Composable
fun AdaptiveContentPane(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tier = LocalWindowWidthTier.current

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                if (!tier.usesCappedContentPane()) {
                    // Compact and XLarge surfaces fill the available width.
                    // XLarge reuses the full-width TV-style composition while
                    // retaining the platform's touch/pointer interaction mode.
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = Dimensions.adaptiveContentMaxWidth)
                        .fillMaxWidth()
                },
        ) {
            content()
        }
    }
}

internal fun WindowWidthTier.usesCappedContentPane(): Boolean = this == WindowWidthTier.Medium || this == WindowWidthTier.Expanded
