// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.hero

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

data class HeroAmbientPresentation(
    val animationOwnerKey: Any?,
    val color: Color?,
    val clearWhenColorMissing: Boolean = false,
)

@Composable
fun Modifier.HeroAmbientBackground(
    presentation: HeroAmbientPresentation,
    navy: Color,
    gradientBottom: Color,
    fallbackBrush: Brush?,
    overlay: Boolean = false,
): Modifier {
    val animated = remember { Animatable(Color.Transparent) }
    LaunchedEffect(presentation.animationOwnerKey, presentation.clearWhenColorMissing, presentation.color) {
        if (presentation.color == null && !presentation.clearWhenColorMissing) return@LaunchedEffect
        animated.animateTo(presentation.color ?: Color.Transparent, tween(HeroTiming.GRADIENT_MILLIS))
    }
    return drawBehind {
        fallbackBrush?.let { drawRect(it) }
        val tint = animated.value
        if (tint.alpha > 0.004f) {
            drawRect(
                Brush.verticalGradient(
                    0f to tint,
                    0.48f to tint.copy(alpha = tint.alpha * 0.44f).compositeOver(navy),
                    1f to gradientBottom,
                ),
            )
        }
        if (overlay) {
            drawRect(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.55f),
                    0.55f to tint.copy(alpha = 0.22f),
                    1f to tint.copy(alpha = 0.08f),
                ),
            )
        }
    }
}
