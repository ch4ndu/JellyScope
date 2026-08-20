// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.jellyscope.core.domain.model.TileSizeId

fun tileScaleFor(
    tier: WindowWidthTier,
    size: TileSizeId,
): Float =
    when (tier) {
        WindowWidthTier.Compact ->
            when (size) {
                TileSizeId.Small -> 0.85f
                TileSizeId.Medium -> 1f
                TileSizeId.Large -> 1.2f
            }
        WindowWidthTier.Medium ->
            when (size) {
                TileSizeId.Small -> 0.9f
                TileSizeId.Medium -> 1.1f
                TileSizeId.Large -> 1.3f
            }
        WindowWidthTier.Expanded ->
            when (size) {
                TileSizeId.Small -> 1f
                TileSizeId.Medium -> 1.2f
                TileSizeId.Large -> 1.45f
            }
        WindowWidthTier.XLarge ->
            when (size) {
                TileSizeId.Small -> 1.1f
                TileSizeId.Medium -> 1.35f
                TileSizeId.Large -> 1.6f
            }
    }

fun tvTileScaleFor(size: TileSizeId): Float =
    when (size) {
        TileSizeId.Small -> 0.85f
        TileSizeId.Medium -> 1f
        TileSizeId.Large -> 1.15f
    }

val LocalTileScale = staticCompositionLocalOf { 1f }

@Composable
fun Dp.tileScaled(): Dp = this * LocalTileScale.current
