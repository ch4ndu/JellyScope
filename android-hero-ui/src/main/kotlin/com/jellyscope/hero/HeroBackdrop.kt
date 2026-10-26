// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.hero

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun <T> HeroBackdrop(
    target: T,
    assetKey: Any?,
    navy: Color,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    HeroContentCrossfade(
        target,
        assetKey,
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
            val horizontal = Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black)
            val vertical = Brush.verticalGradient(0f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent)
            onDrawWithContent {
                drawContent()
                drawRect(horizontal, blendMode = BlendMode.DstIn)
                drawRect(vertical, blendMode = BlendMode.DstIn)
            }
        },
        HeroTiming.ARTWORK_MILLIS,
    ) { layer ->
        if (layer != null) {
            Box(Modifier.fillMaxSize()) {
                content(layer)
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(listOf(navy.copy(alpha = 0.55f), navy.copy(alpha = 0.2f), Color.Transparent)),
                    ),
                )
            }
        }
    }
}
