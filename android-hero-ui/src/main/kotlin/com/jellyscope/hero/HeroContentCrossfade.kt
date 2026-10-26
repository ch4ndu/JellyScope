// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.hero

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics

object HeroTiming {
    const val GRADIENT_MILLIS = 1200
    const val ARTWORK_MILLIS = 650
    const val CONTENT_MILLIS = 600
}

private data class Layer<T>(
    val key: Any?,
    val value: T,
)

/** Two retained layers only; a retarget keeps the more visible layer as outgoing. */
@Composable
fun <T> HeroContentCrossfade(
    target: T,
    assetKey: Any?,
    modifier: Modifier = Modifier,
    durationMillis: Int = HeroTiming.CONTENT_MILLIS,
    content: @Composable (T) -> Unit,
) {
    var current by remember { mutableStateOf(Layer(assetKey, target)) }
    var previous by remember { mutableStateOf<Layer<T>?>(null) }
    val alpha = remember { Animatable(1f) }
    // Metadata updates on the same owner must neither restart nor lose a fade.
    SideEffect { if (current.key == assetKey) current = Layer(assetKey, target) }
    LaunchedEffect(assetKey) {
        if (current.key != assetKey) {
            previous = if (alpha.value < 0.5f) previous ?: current else current
            current = Layer(assetKey, target)
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(durationMillis))
            previous = null
        }
    }
    Box(modifier) {
        previous?.let { outgoing ->
            Box(Modifier.graphicsLayer { this.alpha = 1f - alpha.value }.clearAndSetSemantics {}) { content(outgoing.value) }
        }
        Box(Modifier.graphicsLayer { this.alpha = alpha.value }) {
            content(if (current.key == assetKey) target else current.value)
        }
    }
}
